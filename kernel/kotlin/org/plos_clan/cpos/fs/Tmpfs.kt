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

private class TmpfsInstance(private val options: TmpfsOptions) : SuperBlockBackend {
    private val lock = IrqSpinLock()
    private var nextInodeId = 1uL
    private var allocatedBytes = 0uL

    val pageSize: Int
        get() = options.pageSize

    fun newRegularFile(superBlock: SuperBlock, mode: FileMode): Inode =
        newInode(superBlock, TmpfsRegularFile(this), mode, linkCount = 1u)

    fun newDirectory(superBlock: SuperBlock, mode: FileMode): Inode =
        newInode(superBlock, TmpfsDirectory(this), mode, linkCount = 2u)

    fun newSymlink(superBlock: SuperBlock, target: VfsPathname): Inode =
        newInode(
            superBlock = superBlock,
            backend = TmpfsSymlink(target),
            mode = FileMode(0x1FFu),
            size = target.size.toULong(),
            linkCount = 1u,
        )

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
        mode: FileMode,
        size: ULong = 0uL,
        linkCount: UInt,
    ): Inode {
        val id = lock.withLock { InodeId(nextInodeId++) }
        return Inode(
            id = id,
            superBlock = superBlock,
            backend = backend,
            metadata = InodeMetadata(mode, size, linkCount),
        )
    }
}

private class TmpfsDirectory(private val fileSystem: TmpfsInstance) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY

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
) : TruncatableBackend, ContentBackedFile {
    override val type: InodeType = InodeType.REGULAR

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

    override fun truncate(inode: Inode, size: ULong): VfsResult<Unit> {
        lock.withLock {
            val pageSize = fileSystem.pageSize.toULong()
            val firstRemovedPage = if (size == 0uL) 0uL else (size - 1uL) / pageSize + 1uL
            val removed = pages.keys.filter { it >= firstRemovedPage }
            removed.forEach(pages::remove)
            if (removed.isNotEmpty()) {
                fileSystem.release(removed.size.toULong() * pageSize)
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
        return VfsResult.Ok(Unit)
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
