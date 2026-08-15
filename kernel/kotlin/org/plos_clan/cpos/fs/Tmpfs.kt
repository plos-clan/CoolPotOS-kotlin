package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.IrqSpinLock

data class TmpfsOptions(
    val sizeLimit: ULong? = null,
    val pageSize: Int = 4096,
) : FileSystemOptions {
    init {
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0)
    }
}

object Tmpfs : FileSystemType {
    override val name: String = "tmpfs"
    override val magic: ULong = 0x0102_1994uL
    override val requiresDevice: Boolean = false

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        val configuration = when (options) {
            EmptyFileSystemOptions -> TmpfsOptions()
            is TmpfsOptions -> options
            else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val instance = TmpfsInstance(configuration)
        val superBlock = SuperBlock(this, instance) { sb ->
            instance.newDirectory(sb, FileMode(0x1EDu))
        }
        return VfsResult.Ok(superBlock)
    }
}

internal class TmpfsInstance(
    private val options: TmpfsOptions,
    internal val cacheDirectoryLookups: Boolean = true,
) : SuperBlockBackend {
    private val lock = IrqSpinLock()
    private var nextInodeId = 1uL
    private var allocatedBytes = 0uL

    val pageSize: Int
        get() = options.pageSize

    fun newRegularFile(superBlock: SuperBlock, mode: FileMode): Inode =
        newInode(
            superBlock,
            TmpfsRegularFile(this),
            InodeMetadata(mode = mode, linkCount = 1u),
        )

    fun newDirectory(
        superBlock: SuperBlock,
        mode: FileMode,
        automatic: Boolean = false,
    ): Inode = newInode(
        superBlock,
        TmpfsDirectory(this, automatic),
        InodeMetadata(mode = mode, linkCount = 2u),
    )

    fun newSymlink(superBlock: SuperBlock, target: VfsPathname): Inode =
        newInode(
            superBlock = superBlock,
            backend = TmpfsSymlink(target),
            metadata = InodeMetadata(
                mode = FileMode(0x1FFu),
                size = target.size.toULong(),
                linkCount = 1u,
            ),
        )

    fun installSpecialNode(
        root: Inode,
        path: List<VfsName>,
        backend: InodeBackend,
        metadata: InodeMetadata,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return directory.installSpecialNode(root, path, 0, backend, metadata)
    }

    fun removeSpecialNode(
        root: Inode,
        path: List<VfsName>,
        matches: (InodeBackend) -> Boolean,
    ): Boolean {
        if (path.isEmpty()) return false
        val directory = root.backend as? TmpfsDirectory ?: return false
        return directory.removeSpecialNode(root, path, 0, matches)
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
) : DirectoryBackend, MutableMetadataBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cachePositiveLookups: Boolean
        get() = fileSystem.cacheDirectoryLookups
    override val cacheNegativeLookups: Boolean
        get() = fileSystem.cacheDirectoryLookups

    private val lock = IrqSpinLock()
    private val children = linkedMapOf<VfsName, Inode>()

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        lock.withLock { VfsResult.Ok(children[name]) }

    override fun create(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
        add(name) { fileSystem.newRegularFile(directory.superBlock, mode) }

    override fun mkdir(directory: Inode, name: VfsName, mode: FileMode): VfsResult<Inode> =
        when (val result = add(name) { fileSystem.newDirectory(directory.superBlock, mode) }) {
            is VfsResult.Ok -> {
                directory.updateMetadata { it.copy(linkCount = it.linkCount + 1u) }
                result
            }
            is VfsResult.Err -> result
        }

    override fun symlink(
        directory: Inode,
        name: VfsName,
        target: VfsPathname,
    ): VfsResult<Inode> = add(name) { fileSystem.newSymlink(directory.superBlock, target) }

    override fun unlink(directory: Inode, name: VfsName): VfsResult<Unit> = lock.withLock {
        val inode = children[name] ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        if (inode.type == InodeType.DIRECTORY) {
            return@withLock VfsResult.Err(VfsError.IS_DIRECTORY)
        }
        children.remove(name)
        inode.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
        VfsResult.Ok(Unit)
    }

    override fun rmdir(directory: Inode, name: VfsName): VfsResult<Unit> = lock.withLock {
        val inode = children[name] ?: return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        val child = inode.backend as? TmpfsDirectory
            ?: return@withLock VfsResult.Err(VfsError.NOT_DIRECTORY)
        if (!child.isEmpty()) {
            return@withLock VfsResult.Err(VfsError.NOT_EMPTY)
        }
        children.remove(name)
        directory.updateMetadata { it.copy(linkCount = it.linkCount - 1u) }
        inode.updateMetadata { it.copy(linkCount = 0u) }
        VfsResult.Ok(Unit)
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
            child.updateMetadata { it.copy(linkCount = 0u) }
        }
        removed
    }

    private fun isEmpty(): Boolean = lock.withLock { children.isEmpty() }

    private fun add(name: VfsName, create: () -> Inode): VfsResult<Inode> = lock.withLock {
        if (children.containsKey(name)) {
            return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        val inode = create()
        children[name] = inode
        VfsResult.Ok(inode)
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
) : RegularFileBackend(), MutableMetadataBackend, ContentBackedFile {

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

private class TmpfsSymlink(private val target: VfsPathname) : SymlinkBackend {
    override val type: InodeType = InodeType.SYMLINK

    override fun readLink(inode: Inode): VfsResult<VfsPathname> = VfsResult.Ok(target)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}
