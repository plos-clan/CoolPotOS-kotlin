package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

data class TmpfsOptions(
    val sizeLimit: ULong? = null,
    val pageSize: Int = PAGE_SIZE_BYTES.toInt(),
    val rootMode: FileMode = FileMode(0x1EDu),
    val rootUid: UInt = 0u,
    val rootGid: UInt = 0u,
) : FileSystemOptions {
    init {
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0)
    }

    companion object {
        internal fun parse(data: ByteArray?): VfsResult<TmpfsOptions> {
            if (data == null || data.isEmpty()) return VfsResult.Ok(TmpfsOptions())

            var sizeLimit: ULong? = null
            var mode = 0x1EDu
            var uid = 0u
            var gid = 0u
            for (option in data.decodeToString().split(',')) {
                val separator = option.indexOf('=')
                if (separator <= 0 || separator == option.lastIndex) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val value = option.substring(separator + 1)
                when (option.substring(0, separator)) {
                    "size" -> sizeLimit = parseSize(value)
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "mode" -> mode = value.toUIntOrNull(8)?.takeIf { it <= 0xFFFu }
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "uid" -> uid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "gid" -> gid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
            }
            return VfsResult.Ok(
                TmpfsOptions(
                    sizeLimit = sizeLimit,
                    rootMode = FileMode(mode),
                    rootUid = uid,
                    rootGid = gid,
                ),
            )
        }

        private fun parseSize(value: String): ULong? {
            if (value.endsWith('%')) {
                val percentage = value.dropLast(1).toUIntOrNull()?.takeIf { it <= 100u }
                    ?: return null
                val total = BuddyFrameAllocator.statistics().totalBytes
                val share = percentage.toULong()
                return total / 100uL * share + total % 100uL * share / 100uL
            }
            val shift = when (value.lastOrNull()?.lowercaseChar()) {
                'k' -> 10
                'm' -> 20
                'g' -> 30
                't' -> 40
                'p' -> 50
                'e' -> 60
                else -> 0
            }
            val digits = if (shift == 0) value else value.dropLast(1)
            val units = digits.toULongOrNull() ?: return null
            return units.takeIf { it <= ULong.MAX_VALUE shr shift }?.shl(shift)
        }
    }
}

abstract class TmpfsFileSystemType protected constructor(
    name: String,
    private val backendFactory: (TmpfsOptions) -> SuperBlockBackend,
) : FileSystemType(name, 0x0102_1994uL) {
    final override fun parseOptions(
        source: String?,
        data: ByteArray?,
    ): VfsResult<TmpfsOptions> =
        TmpfsOptions.parse(data)

    final override fun createBackend(
        options: FileSystemOptions,
    ): VfsResult<SuperBlockBackend> {
        val configuration = when (options) {
            EmptyFileSystemOptions -> TmpfsOptions()
            is TmpfsOptions -> options
            else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(backendFactory(configuration))
    }
}

object Tmpfs : TmpfsFileSystemType("tmpfs", ::TmpfsInstance)

internal open class TmpfsInstance(
    private val options: TmpfsOptions,
    internal val cacheDirectoryLookups: Boolean = true,
) : SuperBlockBackend {
    private val lock = IrqSpinLock()
    private val mutationLock = IrqSpinLock()
    private var nextInodeId = 1uL
    private var allocatedBytes = 0uL

    val pageSize: Int
        get() = options.pageSize

    override fun createRoot(superBlock: SuperBlock): Inode = newDirectory(
        superBlock = superBlock,
        mode = options.rootMode,
        uid = options.rootUid,
        gid = options.rootGid,
        parent = null,
    )

    fun newRegularFile(
        superBlock: SuperBlock,
        mode: FileMode,
        uid: UInt = 0u,
        gid: UInt = 0u,
    ): Inode =
        newInode(
            superBlock,
            TmpfsRegularFile(this),
            InodeMetadata(mode = mode, linkCount = 1u, uid = uid, gid = gid),
        )

    fun newDirectory(
        superBlock: SuperBlock,
        mode: FileMode,
        uid: UInt = 0u,
        gid: UInt = 0u,
        automatic: Boolean = false,
        parent: Inode?,
    ): Inode = newInode(
        superBlock,
        TmpfsDirectory(this, automatic, parent),
        InodeMetadata(mode = mode, linkCount = 2u, uid = uid, gid = gid),
    )

    fun newSymlink(
        superBlock: SuperBlock,
        target: VfsPathname,
        uid: UInt = 0u,
        gid: UInt = 0u,
    ): Inode =
        newInode(
            superBlock = superBlock,
            backend = TmpfsSymlink(target),
            metadata = InodeMetadata(
                mode = FileMode(0x1FFu),
                size = target.size.toULong(),
                linkCount = 1u,
                uid = uid,
                gid = gid,
            ),
        )

    fun newNode(superBlock: SuperBlock, node: NodeCreation, parent: Inode): Inode =
        when (val kind = node.kind) {
            NodeKind.Regular -> newRegularFile(superBlock, node.mode, node.uid, node.gid)
            NodeKind.Directory -> newDirectory(
                superBlock,
                node.mode,
                node.uid,
                node.gid,
                parent = parent,
            )
            NodeKind.Fifo -> newInode(
                superBlock,
                FifoBackend(),
                InodeMetadata(node.mode, linkCount = 1u, uid = node.uid, gid = node.gid),
            )
            NodeKind.Socket -> newInode(
                superBlock,
                SocketNodeBackend,
                InodeMetadata(node.mode, linkCount = 1u, uid = node.uid, gid = node.gid),
            )
            is NodeKind.SymbolicLink -> newSymlink(superBlock, kind.target, node.uid, node.gid)
            is NodeKind.Device -> newInode(
                superBlock,
                DeviceNode(kind.type, kind.number),
                InodeMetadata(
                    mode = node.mode,
                    linkCount = 1u,
                    deviceNumber = kind.number,
                    uid = node.uid,
                    gid = node.gid,
                ),
            )
        }

    fun installSpecialNode(
        root: Inode,
        path: List<VfsName>,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return mutate { directory.installSpecialNode(root, path, 0, backend, metadata) }
    }

    fun removeSpecialNode(
        root: Inode,
        path: List<VfsName>,
        matches: (InodeBackend) -> Boolean,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return mutate { directory.removeSpecialNode(root, path, 0, matches) }
    }

    fun reserve(bytes: ULong): Boolean = lock.withLock {
        val limit = options.sizeLimit
        if (bytes > ULong.MAX_VALUE - allocatedBytes ||
            (limit != null && allocatedBytes + bytes > limit)
        ) {
            false
        } else {
            allocatedBytes += bytes
            true
        }
    }

    fun release(bytes: ULong) {
        lock.withLock {
            check(bytes <= allocatedBytes)
            allocatedBytes -= bytes
        }
    }

    fun <T> mutate(operation: () -> T): T = mutationLock.withLock(operation)

    private fun newInode(
        superBlock: SuperBlock,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode {
        val id = lock.withLock { InodeId(nextInodeId++) }
        return Inode(
            id = id,
            superBlock = superBlock,
            backend = backend,
            metadata = metadata,
        )
    }

    internal fun newSpecialNode(
        superBlock: SuperBlock,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Inode = newInode(superBlock, backend, metadata)
}

internal class TmpfsDirectory(
    private val fileSystem: TmpfsInstance,
    private val automatic: Boolean,
    private var parent: Inode?,
) : DirectoryBackend, MutableInodeBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cachePositiveLookups: Boolean
        get() = fileSystem.cacheDirectoryLookups
    override val cacheNegativeLookups: Boolean
        get() = fileSystem.cacheDirectoryLookups

    private val lock = IrqSpinLock()
    private val children = linkedMapOf<VfsName, Inode>()

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        lock.withLock { VfsResult.Ok(children[name]) }

    override fun create(
        directory: Inode,
        name: VfsName,
        node: NodeCreation,
    ): VfsResult<Inode> = fileSystem.mutate {
        lock.withLock {
            if (children.containsKey(name)) {
                return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
            }
            if (node.kind == NodeKind.Directory &&
                directory.metadata().linkCount == UInt.MAX_VALUE
            ) {
                return@withLock VfsResult.Err(VfsError.TOO_MANY_LINKS)
            }
            val inode = fileSystem.newNode(directory.superBlock, node, directory)
            children[name] = inode
            if (node.kind == NodeKind.Directory) {
                directory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
            }
            VfsResult.Ok(inode)
        }
    }

    override fun link(directory: Inode, name: VfsName, target: Inode): VfsResult<Unit> =
        fileSystem.mutate {
            lock.withLock {
                if (children.containsKey(name)) {
                    return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
                }
                val links = target.metadata().linkCount
                if (links == UInt.MAX_VALUE) {
                    return@withLock VfsResult.Err(VfsError.TOO_MANY_LINKS)
                }
                children[name] = target
                target.updateMetadata { it.copy(linkCount = links + 1u) }
                VfsResult.Ok(Unit)
            }
        }

    override fun rename(
        sourceDirectory: Inode,
        sourceName: VfsName,
        source: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val targetBackend = targetDirectory.backend as? TmpfsDirectory
            ?: return VfsResult.Err(VfsError.CROSS_DEVICE)
        if (targetBackend.fileSystem !== fileSystem) return VfsResult.Err(VfsError.CROSS_DEVICE)
        return fileSystem.mutate {
            withLocks(sourceDirectory, targetDirectory, targetBackend) {
                renameLocked(
                    sourceDirectory,
                    sourceName,
                    source,
                    targetDirectory,
                    targetName,
                    targetBackend,
                    target,
                    mode,
                )
            }
        }
    }

    override fun remove(
        directory: Inode,
        name: VfsName,
        target: Inode,
        mode: RemoveMode,
    ): VfsResult<Unit> = fileSystem.mutate {
        lock.withLock {
            val inode = children[name] ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            if (inode !== target) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
            if (mode == RemoveMode.DIRECTORY) {
                val child = inode.backend as? TmpfsDirectory
                    ?: return@withLock VfsResult.Err(VfsError.NOT_DIRECTORY)
                if (!child.isEmpty()) return@withLock VfsResult.Err(VfsError.NOT_EMPTY)
                directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                child.parent = null
                inode.updateMetadata { it.copy(linkCount = 0u) }
            } else {
                if (inode.type == InodeType.DIRECTORY) {
                    return@withLock VfsResult.Err(VfsError.IS_DIRECTORY)
                }
                inode.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            }
            children.remove(name)
            VfsResult.Ok(Unit)
        }
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(TmpfsDirectoryHandle(this))

    fun snapshot(): List<DirectoryEntry> = lock.withLock {
        children.map { (name, inode) -> DirectoryEntry(name, inode.id, inode.type) }
    }

    fun installSpecialNode(
        directory: Inode,
        path: List<VfsName>,
        index: Int,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Boolean = lock.withLock {
        val name = path[index]
        if (index == path.lastIndex) {
            if (children.containsKey(name)) return@withLock false
            children[name] = fileSystem.newSpecialNode(directory.superBlock, backend, metadata)
            return@withLock true
        }

        var child = children[name]
        var created = false
        if (child == null) {
            child = fileSystem.newDirectory(
                directory.superBlock,
                FileMode(0x1EDu),
                automatic = true,
                parent = directory,
            )
            children[name] = child
            directory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
            created = true
        }
        val childDirectory = child.backend as? TmpfsDirectory ?: return@withLock false
        val installed = childDirectory.installSpecialNode(
            child,
            path,
            index + 1,
            backend,
            metadata,
        )
        if (!installed && created && childDirectory.isEmpty()) {
            children.remove(name)
            directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            childDirectory.parent = null
            child.updateMetadata { it.copy(linkCount = 0u) }
        }
        installed
    }

    fun removeSpecialNode(
        directory: Inode,
        path: List<VfsName>,
        index: Int,
        matches: (InodeBackend) -> Boolean,
    ): Boolean = lock.withLock {
        val name = path[index]
        val child = children[name] ?: return@withLock false
        if (index == path.lastIndex) {
            if (!matches(child.backend)) return@withLock false
            children.remove(name)
            child.updateMetadata { it.copy(linkCount = 0u) }
            return@withLock true
        }

        val childDirectory = child.backend as? TmpfsDirectory ?: return@withLock false
        val removed = childDirectory.removeSpecialNode(
            child,
            path,
            index + 1,
            matches,
        )
        if (removed && childDirectory.automatic && childDirectory.isEmpty()) {
            children.remove(name)
            directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            childDirectory.parent = null
            child.updateMetadata { it.copy(linkCount = 0u) }
        }
        removed
    }

    private fun isEmpty(): Boolean = lock.withLock { children.isEmpty() }

    private fun renameLocked(
        sourceDirectory: Inode,
        sourceName: VfsName,
        expectedSource: Inode,
        targetDirectory: Inode,
        targetName: VfsName,
        target: TmpfsDirectory,
        expectedTarget: Inode?,
        mode: RenameMode,
    ): VfsResult<Unit> {
        val source = children[sourceName] ?: return VfsResult.Err(VfsError.NOT_FOUND)
        if (source !== expectedSource) return VfsResult.Err(VfsError.NOT_FOUND)
        val replaced = target.children[targetName]
        if (replaced !== expectedTarget) return VfsResult.Err(VfsError.NOT_FOUND)
        if (this === target && sourceName == targetName) return VfsResult.Ok(Unit)
        if (mode == RenameMode.NO_REPLACE && replaced != null) {
            return VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (source === replaced) return VfsResult.Ok(Unit)
        if (source.type == InodeType.DIRECTORY && isWithin(targetDirectory, source)) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (mode == RenameMode.EXCHANGE) {
            val exchanged = replaced ?: return VfsResult.Err(VfsError.NOT_FOUND)
            if (exchanged.type == InodeType.DIRECTORY && isWithin(sourceDirectory, exchanged)) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (this !== target) {
                val gainsDirectory = if (source.type == InodeType.DIRECTORY) {
                    targetDirectory.takeIf { exchanged.type != InodeType.DIRECTORY }
                } else {
                    sourceDirectory.takeIf { exchanged.type == InodeType.DIRECTORY }
                }
                if (gainsDirectory?.metadata()?.linkCount == UInt.MAX_VALUE) {
                    return VfsResult.Err(VfsError.TOO_MANY_LINKS)
                }
            }
            children[sourceName] = exchanged
            target.children[targetName] = source
            if (this !== target) {
                val sourceIsDirectory = source.type == InodeType.DIRECTORY
                val targetIsDirectory = exchanged.type == InodeType.DIRECTORY
                if (sourceIsDirectory) {
                    (source.backend as TmpfsDirectory).parent = targetDirectory
                }
                if (targetIsDirectory) {
                    (exchanged.backend as TmpfsDirectory).parent = sourceDirectory
                }
                if (sourceIsDirectory != targetIsDirectory) {
                    if (sourceIsDirectory) {
                        sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                        targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
                    } else {
                        sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
                        targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                    }
                }
            }
            return VfsResult.Ok(Unit)
        }

        if (replaced != null) {
            val sourceIsDirectory = source.type == InodeType.DIRECTORY
            if (sourceIsDirectory != (replaced.type == InodeType.DIRECTORY)) {
                return VfsResult.Err(
                    if (sourceIsDirectory) VfsError.NOT_DIRECTORY else VfsError.IS_DIRECTORY,
                )
            }
            val replacedDirectory = replaced.backend as? TmpfsDirectory
            if (replacedDirectory != null && !replacedDirectory.isEmpty()) {
                return VfsResult.Err(VfsError.NOT_EMPTY)
            }
        }
        if (source.type == InodeType.DIRECTORY && this !== target && replaced == null &&
            targetDirectory.metadata().linkCount == UInt.MAX_VALUE
        ) {
            return VfsResult.Err(VfsError.TOO_MANY_LINKS)
        }

        children.remove(sourceName)
        target.children[targetName] = source
        if (source.type == InodeType.DIRECTORY && this !== target) {
            (source.backend as TmpfsDirectory).parent = targetDirectory
            sourceDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
        }
        if (replaced != null) {
            if (replaced.type == InodeType.DIRECTORY) {
                targetDirectory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
                (replaced.backend as TmpfsDirectory).parent = null
                replaced.updateMetadata { it.copy(linkCount = 0u) }
            } else {
                replaced.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
            }
        }
        return VfsResult.Ok(Unit)
    }

    private fun isWithin(directory: Inode, ancestor: Inode): Boolean {
        var current: Inode? = directory
        while (current != null) {
            if (current === ancestor) return true
            current = (current.backend as? TmpfsDirectory)?.parent
        }
        return false
    }

    private fun <T> withLocks(
        source: Inode,
        target: Inode,
        targetBackend: TmpfsDirectory,
        operation: () -> T,
    ): T {
        if (this === targetBackend) return lock.withLock(operation)
        return if (source.id.value < target.id.value) {
            lock.withLock { targetBackend.lock.withLock(operation) }
        } else {
            targetBackend.lock.withLock { lock.withLock(operation) }
        }
    }
}

private class TmpfsDirectoryHandle(private val directory: TmpfsDirectory) : OpenFileBackend {
    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        val entries = directory.snapshot()
        if (position.value > Int.MAX_VALUE) {
            return VfsResult.Ok(Unit)
        }
        var index = position.value.coerceAtLeast(0).toInt()
        while (index < entries.size) {
            val nextOffset = index.toLong() + 1L
            if (!emit(entries[index], nextOffset)) {
                break
            }
            index++
            position.value = nextOffset
        }
        return VfsResult.Ok(Unit)
    }
}

private class TmpfsRegularFile(
    private val fileSystem: TmpfsInstance,
) : RegularFileBackend(), MutableInodeBackend, ContentBackedFile {

    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<ULong, ByteArray>()
    private var content: FileContent? = null
    private var contentOffset = 0
    private var contentSize = 0

    override fun attachContent(
        inode: Inode,
        content: FileContent,
        offset: Int,
        size: Int,
    ): Boolean {
        val attached = lock.withLock {
            if (this.content != null || pages.isNotEmpty() ||
                offset < 0 || size < 0 || offset > content.size - size
            ) {
                return@withLock false
            }
            this.content = content
            contentOffset = offset
            contentSize = size
            true
        }
        if (attached) {
            inode.updateMetadata { it.copy(size = size.toULong()) }
        }
        return attached
    }

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(TmpfsRegularHandle(this))

    override fun resize(inode: Inode, size: ULong): VfsResult<Unit> = lock.withLock {
        if (size < inode.metadata().size) {
            val pageSize = fileSystem.pageSize.toULong()
            val firstRemovedPage = if (size == 0uL) 0uL else (size - 1uL) / pageSize + 1uL
            var removedPages = 0uL
            val iterator = pages.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key >= firstRemovedPage) {
                    iterator.remove()
                    removedPages++
                }
            }
            if (removedPages != 0uL) {
                fileSystem.release(removedPages * pageSize)
            }

            if (size != 0uL) {
                val tail = (size % pageSize).toInt()
                if (tail != 0) {
                    pages[size / pageSize]?.fill(0, tail)
                }
            }
            contentSize = minOf(contentSize.toULong(), size).toInt()
        }
        inode.updateMetadata { it.copy(size = size) }
        VfsResult.Ok(Unit)
    }

    override fun allocate(
        inode: Inode,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> = lock.withLock {
        val pageSize = fileSystem.pageSize.toULong()
        val end = offset + length
        val firstPage = offset / pageSize
        val lastPage = (end - 1uL) / pageSize

        val requestedPages = lastPage - firstPage + 1uL
        var existingPages = 0uL
        if (requestedPages <= pages.size.toULong()) {
            var pageIndex = firstPage
            while (true) {
                if (pageIndex in pages) existingPages++
                if (pageIndex == lastPage) break
                pageIndex++
            }
        } else {
            for (pageIndex in pages.keys) {
                if (pageIndex >= firstPage && pageIndex <= lastPage) existingPages++
            }
        }
        val missingPages = requestedPages - existingPages
        if (missingPages == 0uL) {
            if (!mode.keepsSize) inode.updateMetadata { it.copy(size = maxOf(it.size, end)) }
            return@withLock VfsResult.Ok(Unit)
        }
        if (missingPages > Int.MAX_VALUE.toULong() ||
            missingPages > ULong.MAX_VALUE / pageSize
        ) {
            return@withLock VfsResult.Err(VfsError.NO_SPACE)
        }

        val reservedBytes = missingPages * pageSize
        if (!fileSystem.reserve(reservedBytes)) {
            return@withLock VfsResult.Err(VfsError.NO_SPACE)
        }
        val added = try {
            ArrayList<ULong>(missingPages.toInt())
        } catch (_: OutOfMemoryError) {
            fileSystem.release(reservedBytes)
            return@withLock VfsResult.Err(VfsError.NO_MEMORY)
        }
        try {
            var pageIndex = firstPage
            while (true) {
                if (pageIndex !in pages) {
                    val page = ByteArray(fileSystem.pageSize)
                    val destination = checkNotNull(ByteArrayBuffer(page).prepareWrite(0, page.size))
                    copyContent(pageIndex * pageSize, destination, 0, page.size)
                    added += pageIndex
                    pages[pageIndex] = page
                }
                if (pageIndex == lastPage) break
                pageIndex++
            }
        } catch (_: OutOfMemoryError) {
            added.forEach(pages::remove)
            fileSystem.release(reservedBytes)
            return@withLock VfsResult.Err(VfsError.NO_MEMORY)
        }
        if (!mode.keepsSize) inode.updateMetadata { it.copy(size = maxOf(it.size, end)) }
        VfsResult.Ok(Unit)
    }

    override fun allocatedBlocks(inode: Inode): ULong = lock.withLock {
        val pageSize = fileSystem.pageSize.toULong()
        val contentPages = if (contentSize == 0) {
            0uL
        } else {
            (contentSize.toULong() - 1uL) / pageSize + 1uL
        }
        var allocatedPages = contentPages
        for (pageIndex in pages.keys) {
            if (pageIndex >= contentPages) allocatedPages++
        }
        val allocatedBytes = allocatedPages * pageSize
        allocatedBytes / ALLOCATION_BLOCK_SIZE +
            if (allocatedBytes % ALLOCATION_BLOCK_SIZE == 0uL) 0uL else 1uL
    }

    override fun evict(inode: Inode) {
        val releasedPages = lock.withLock {
            val count = pages.size
            pages.clear()
            content = null
            count
        }
        if (releasedPages != 0) {
            fileSystem.release(releasedPages.toULong() * fileSystem.pageSize.toULong())
        }
    }

    fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = lock.withLock {
        val size = inode.metadata().size
        if (position.value < 0 || position.value.toULong() >= size || count == 0) {
            return@withLock IoResult.success(0)
        }

        val available = minOf(count.toULong(), size - position.value.toULong()).toInt()
        var copied = 0
        while (copied < available) {
            val absolute = position.value.toULong() + copied.toULong()
            val pageIndex = absolute / fileSystem.pageSize.toULong()
            val pageOffset = (absolute % fileSystem.pageSize.toULong()).toInt()
            val chunk = minOf(available - copied, fileSystem.pageSize - pageOffset)
            val transferred = pages[pageIndex]?.let { page ->
                destination.copyFrom(destinationOffset + copied, page, pageOffset, chunk)
            } ?: readContentOrZero(absolute, destination, destinationOffset + copied, chunk)
            if (transferred == 0) {
                if (copied == 0) return@withLock IoResult.failure(VfsError.FAULT)
                break
            }
            copied += transferred
            if (transferred < chunk) break
        }
        position.value += copied
        IoResult.success(copied)
    }

    fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = lock.withLock {
        var cursor = if (append) inode.metadata().size else position.value.toULong()
        if (position.value < 0 || cursor > Long.MAX_VALUE.toULong() ||
            count.toLong() > Long.MAX_VALUE - cursor.toLong()
        ) {
            return@withLock IoResult.failure(VfsError.FILE_TOO_LARGE)
        }

        var copied = 0
        var noSpace = false
        while (copied < count) {
            val pageIndex = cursor / fileSystem.pageSize.toULong()
            val pageOffset = (cursor % fileSystem.pageSize.toULong()).toInt()
            val chunk = minOf(count - copied, fileSystem.pageSize - pageOffset)
            var page = pages[pageIndex]
            if (page == null) {
                if (!fileSystem.reserve(fileSystem.pageSize.toULong())) {
                    noSpace = true
                    break
                }
                page = ByteArray(fileSystem.pageSize)
                val destination = checkNotNull(ByteArrayBuffer(page).prepareWrite(0, page.size))
                copyContent(
                    pageIndex * fileSystem.pageSize.toULong(),
                    destination,
                    0,
                    page.size,
                )
                pages[pageIndex] = page
            }
            val transferred = source.copyTo(sourceOffset + copied, page, pageOffset, chunk)
            if (transferred == 0) break
            cursor += transferred.toULong()
            copied += transferred
            if (transferred < chunk) break
        }

        if (copied == 0 && count != 0) {
            return@withLock IoResult.failure(if (noSpace) VfsError.NO_SPACE else VfsError.FAULT)
        }
        position.value = cursor.toLong()
        inode.updateMetadata { it.copy(size = maxOf(it.size, cursor)) }
        IoResult.success(copied)
    }

    private fun copyContent(
        position: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Int {
        val source = content ?: return 0
        if (position > Int.MAX_VALUE.toULong()) return 0
        val sourcePosition = position.toInt()
        val copied = minOf(count, contentSize - sourcePosition).coerceAtLeast(0)
        if (copied != 0) {
            return source.copyInto(
                destination,
                destinationOffset,
                contentOffset + sourcePosition,
                copied,
            )
        }
        return 0
    }

    private fun readContentOrZero(
        position: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Int {
        val contentBytes = contentBytes(position, count)
        val copied = copyContent(position, destination, destinationOffset, contentBytes)
        if (copied < contentBytes) return copied
        return copied + destination.fill(destinationOffset + copied, count - copied)
    }

    private fun contentBytes(position: ULong, count: Int): Int {
        if (content == null || position > Int.MAX_VALUE.toULong()) return 0
        return minOf(count, contentSize - position.toInt()).coerceAtLeast(0)
    }
}

private class TmpfsRegularHandle(private val file: TmpfsRegularFile) : OpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult = file.read(inode, destination, destinationOffset, count, position)

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult = file.write(inode, source, sourceOffset, count, position, append)
}

private class TmpfsSymlink(
    private val target: VfsPathname,
) : SymlinkBackend, MutableInodeBackend {
    override val type: InodeType = InodeType.SYMLINK

    override fun readLink(inode: Inode): VfsResult<VfsPathname> = VfsResult.Ok(target)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}
