package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.module.ModuleData
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer

private const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

data class ErofsOptions(
    val data: ModuleData,
    val cacheBytes: Int = DEFAULT_CACHE_BYTES,
) : FileSystemOptions

object Erofs : FileSystemType("erofs", 0xe0f5_e1e2uL, requiresDevice = true) {
    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> {
        val configuration = options as? ErofsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (configuration.cacheBytes < 0) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val instance = ErofsInstance.open(configuration.data, configuration.cacheBytes)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(instance)
    }
}

private class ErofsInstance private constructor(
    private val image: Image,
    private val header: Header,
    private val packed: PackedData,
) : SuperBlockBackend {
    companion object {
        fun open(data: ModuleData, cacheBytes: Int): ErofsInstance? {
            val image = Image(data)
            val header = Header.read(image) ?: return null
            val packed = PackedData.open(image, header, cacheBytes) ?: return null
            return ErofsInstance(image, header, packed)
        }
    }

    private val inodeLock = IrqSpinLock()
    private val inodeCache = mutableMapOf<ULong, Inode>()

    override fun createRoot(superBlock: SuperBlock): Inode =
        inode(superBlock, header.rootNid) ?: error("EROFS root inode is invalid")

    override fun updateTimestamps(
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.READ_ONLY)

    private fun inode(superBlock: SuperBlock, nid: ULong): Inode? {
        inodeLock.withLock { inodeCache[nid] }?.let { return it }
        val node = readNode(nid) ?: return null
        val backend = when (node) {
            is DirectoryNode -> DirectoryBackend(this, superBlock, node)
            is FileNode -> RegularBackend(this, node)
            is SymlinkNode -> SymlinkBackend(node.target)
        }
        val candidate = Inode(InodeId(nid), superBlock, backend, node.metadata)
        return inodeLock.withLock {
            inodeCache[nid] ?: candidate.also { inodeCache[nid] = it }
        }
    }

    private fun readNode(nid: ULong): Node? {
        val inode = DiskInode.read(image, header, nid) ?: return null
        return when (inode.type) {
            DiskFileType.DIRECTORY -> flatData(inode)?.let {
                DirectoryNode(inode.metadata, it)
            }
            DiskFileType.SYMLINK -> flatData(inode)?.readAll()?.let {
                SymlinkNode(inode.metadata, VfsPathname.fromBytes(it))
            }
            DiskFileType.REGULAR -> fileNode(inode)
        }
    }

    private fun flatData(inode: DiskInode): FlatData? {
        if (inode.layout != DataLayout.FLAT_PLAIN && inode.layout != DataLayout.FLAT_INLINE) {
            return null
        }
        return FlatData(
            image = image,
            blockSize = header.blockSize,
            inodeLocation = inode.location,
            inodeSize = inode.inodeSize,
            rawBlock = inode.rawBlock,
            size = inode.size,
            inline = inode.layout == DataLayout.FLAT_INLINE,
        ).takeIf(FlatData::valid)
    }

    private fun fileNode(inode: DiskInode): FileNode? {
        if (inode.size == 0uL) return FileNode(inode.metadata, Fragment(0uL, 0uL))
        if (inode.layout != DataLayout.COMPRESSED_FULL &&
            inode.layout != DataLayout.COMPRESSED_COMPACT
        ) {
            return null
        }
        val mapHeader = image.align(inode.location + inode.inodeSize.toULong(), 8) ?: return null
        if (!image.contains(mapHeader, 8)) return null
        val fragmentHeader = image.u64(mapHeader)
        if (fragmentHeader and Header.FRAGMENT_INODE_FLAG == 0uL) return null
        val fragment = Fragment(
            fragmentHeader xor Header.FRAGMENT_INODE_FLAG,
            inode.size,
        )
        return FileNode(inode.metadata, fragment).takeIf {
            fragment.offset <= packed.size && fragment.size <= packed.size - fragment.offset
        }
    }

    private fun directoryData(node: DirectoryNode): DirectoryData? {
        inodeLock.withLock { node.cached }?.let { return it }
        val bytes = node.data.readAll() ?: return null
        val input = LittleEndianBuffer(bytes)
        val entries = mutableListOf<Entry>()
        var blockStart = 0
        while (blockStart < bytes.size) {
            val blockLength = minOf(header.blockSize, bytes.size - blockStart)
            if (blockLength < Header.DIRENT_SIZE) return null
            val firstName = input.readU16(blockStart + 8).toInt()
            if (firstName < Header.DIRENT_SIZE || firstName > blockLength ||
                firstName % Header.DIRENT_SIZE != 0
            ) return null
            val count = firstName / Header.DIRENT_SIZE
            for (index in 0 until count) {
                val entryOffset = blockStart + index * Header.DIRENT_SIZE
                val nameStart = input.readU16(entryOffset + 8).toInt()
                val nameEnd = if (index + 1 < count) {
                    input.readU16(entryOffset + Header.DIRENT_SIZE + 8).toInt()
                } else {
                    var end = blockLength
                    while (end > nameStart && bytes[blockStart + end - 1] == 0.toByte()) end--
                    end
                }
                if (nameStart !in firstName..<nameEnd || nameEnd > blockLength ||
                    nameEnd - nameStart > Header.MAX_NAME_LENGTH
                ) return null
                val type = inodeType(bytes[entryOffset + 10].toInt() and 0xff)
                    ?: return null
                entries += Entry(
                    VfsName.fromPath(bytes, blockStart + nameStart, blockStart + nameEnd),
                    input.readU64(entryOffset),
                    type,
                )
            }
            blockStart += blockLength
        }
        val parsed = DirectoryData(entries)
        return inodeLock.withLock {
            node.cached ?: parsed.also { node.cached = it }
        }
    }

    private fun inodeType(type: Int): InodeType? = when (type) {
        1 -> InodeType.REGULAR
        2 -> InodeType.DIRECTORY
        7 -> InodeType.SYMLINK
        else -> null
    }

    private sealed class Node(val metadata: InodeMetadata)

    private class DirectoryNode(metadata: InodeMetadata, val data: FlatData) : Node(metadata) {
        var cached: DirectoryData? = null
    }

    private class FileNode(metadata: InodeMetadata, val fragment: Fragment) : Node(metadata)

    private class SymlinkNode(metadata: InodeMetadata, val target: VfsPathname) : Node(metadata)

    private data class Fragment(val offset: ULong, val size: ULong)

    private data class Entry(
        val name: VfsName,
        val nid: ULong,
        val type: InodeType,
    )

    private class DirectoryData(entries: List<Entry>) {
        val byName = entries.associateBy(Entry::name)
        val entries = entries.map {
            DirectoryEntry(it.name, InodeId(it.nid), it.type)
        }
    }

    private class DirectoryBackend(
        private val instance: ErofsInstance,
        private val superBlock: SuperBlock,
        private val node: DirectoryNode,
    ) : org.plos_clan.cpos.fs.DirectoryBackend {
        override val type: InodeType = InodeType.DIRECTORY

        override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> {
            val entry = instance.directoryData(node)?.byName?.get(name)
                ?: return VfsResult.Ok(null)
            val inode = instance.inode(superBlock, entry.nid)
                ?: return VfsResult.Err(VfsError.IO)
            return VfsResult.Ok(inode)
        }

        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
            val entries = instance.directoryData(node)?.entries
                ?: return VfsResult.Err(VfsError.IO)
            return VfsResult.Ok(DirectoryHandle(entries))
        }
    }

    private class DirectoryHandle(private val entries: List<DirectoryEntry>) : OpenFileBackend {
        override fun iterate(
            inode: Inode,
            position: FilePosition,
            emit: (DirectoryEntry, Long) -> Boolean,
        ): VfsResult<Unit> {
            var index = position.value.coerceAtLeast(0).toInt()
            while (index < entries.size) {
                val next = index.toLong() + 1
                if (!emit(entries[index], next)) break
                position.value = next
                index++
            }
            return VfsResult.Ok(Unit)
        }
    }

    private class RegularBackend(
        instance: ErofsInstance,
        node: FileNode,
    ) : RegularFileBackend(), CachedFileBackend {
        private val packed = instance.packed
        private val fragment = node.fragment

        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Ok(this)

        override fun read(offset: ULong, destination: ByteArray): Int {
            if (offset >= fragment.size) return 0
            val count = minOf(destination.size.toULong(), fragment.size - offset).toInt()
            val target = checkNotNull(ByteArrayBuffer(destination).prepareWrite(0, count))
            return packed.read(fragment.offset + offset, target, 0, count) ?: -1
        }
    }

    private class SymlinkBackend(
        private val target: VfsPathname,
    ) : org.plos_clan.cpos.fs.SymlinkBackend {
        override val type: InodeType = InodeType.SYMLINK
        override fun readLink(inode: Inode): VfsResult<VfsPathname> = VfsResult.Ok(target)
        override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
            VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
    }
}
