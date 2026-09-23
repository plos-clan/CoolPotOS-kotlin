package org.plos_clan.cpos.fs.erofs

import org.plos_clan.cpos.fs.DeviceNode
import org.plos_clan.cpos.fs.sock.SocketNodeBackend
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.CachedFileBackend
import org.plos_clan.cpos.fs.vfs.DirectoryBackend
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.FifoBackend
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.FileSystemType
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.InodeBackend
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestampUpdate
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.SuperBlockBackend
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.block.ByteSource
import org.plos_clan.cpos.block.BlockDevices
import org.plos_clan.cpos.block.BlockDeviceBackend
import org.plos_clan.cpos.fs.vfs.FileSystemParameters
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.LittleEndianBuffer

private const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

data class ErofsOptions(
    val data: ByteSource,
    val cacheBytes: Int = DEFAULT_CACHE_BYTES,
) : FileSystemOptions

object Erofs : FileSystemType("erofs", 0xe0f5_e1e2uL, requiresDevice = true) {
    override fun configure(source: String?, parameters: FileSystemParameters): VfsResult<FileSystemOptions> {
        if (source == null || !parameters.isEmpty()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val device = BlockDevices.find(source) ?: return VfsResult.Err(VfsError.NO_DEVICE)
        val volume = (device.backend as BlockDeviceBackend).volume
        return VfsResult.Ok(ErofsOptions(volume.openReadOnly()))
    }

    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> {
        val configuration = options as? ErofsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (configuration.cacheBytes < 0) {
            configuration.data.close()
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val instance = try {
            ErofsInstance.open(configuration.data, configuration.cacheBytes)
        } catch (_: IllegalStateException) {
            null
        }
        if (instance != null) return VfsResult.Ok(instance)
        configuration.data.close()
        return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    }
}

private class ErofsInstance private constructor(
    private val image: Image,
    private val header: Header,
    private val packed: FileData,
    private val cache: ExtentCache<Extent.Stored>,
) : SuperBlockBackend {
    companion object {
        fun open(data: ByteSource, cacheBytes: Int): ErofsInstance? {
            val image = Image(data)
            val header = Header.read(image) ?: return null
            val inode = DiskInode.read(image, header, header.packedNid) ?: return null
            val cache = ExtentCache<Extent.Stored>(cacheBytes)
            val packed = CompressedData.open(image, header, inode, cache) ?: return null
            return ErofsInstance(image, header, packed, cache)
        }
    }

    override fun release() = image.close()

    private val inodeLock = IrqSpinLock()
    private val inodeCache = mutableMapOf<ULong, Inode>()

    override fun createRoot(superBlock: SuperBlock): Inode =
        inode(superBlock, header.rootNid) ?: error("EROFS root inode is invalid")

    override fun updateTimestamps(
        caller: VfsOperationContext,
        inode: Inode,
        update: InodeTimestampUpdate,
    ): VfsResult<Unit> = VfsResult.Err(VfsError.READ_ONLY)

    private fun inode(superBlock: SuperBlock, nid: ULong): Inode? {
        inodeLock.withLock { inodeCache[nid] }?.let { return it }
        val node = readNode(nid) ?: return null
        val backend = when (node) {
            is DirectoryNode -> ErofsDirectoryBackend(this, superBlock, node)
            is FileNode -> RegularBackend(node)
            is SymlinkNode -> ErofsSymlinkBackend(node.target)
            is SpecialNode -> node.backend
        }
        val attributes = InodeAttributeSnapshot(
            InodeAttributes(node.metadata),
            CacheValidity.Persistent,
        )
        val candidate = Inode(InodeId(nid), superBlock, backend, attributes)
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
            DiskFileType.REGULAR -> fileNode(inode)
            DiskFileType.SYMLINK -> flatData(inode)?.readAll()?.let {
                SymlinkNode(inode.metadata, VfsPathname.fromBytes(it))
            }
            DiskFileType.CHARACTER_DEVICE,
            DiskFileType.BLOCK_DEVICE,
            -> SpecialNode(
                inode.metadata,
                DeviceNode(inode.type.inodeType, inode.metadata.deviceNumber),
            )
            DiskFileType.PIPE -> SpecialNode(inode.metadata, FifoBackend())
            DiskFileType.SOCKET -> SpecialNode(inode.metadata, SocketNodeBackend)
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
        if (inode.layout == DataLayout.FLAT_PLAIN || inode.layout == DataLayout.FLAT_INLINE) {
            return flatData(inode)?.let { FileNode(inode.metadata, it) }
        }
        if (inode.size == 0uL) {
            val data = FragmentData(packed, 0uL, 0uL)
            return FileNode(inode.metadata, data)
        }
        if (inode.layout != DataLayout.COMPRESSED_FULL &&
            inode.layout != DataLayout.COMPRESSED_COMPACT
        ) {
            return null
        }
        val mapHeader = (inode.location + inode.inodeSize.toULong()).alignUp(8uL) ?: return null
        if (!image.contains(mapHeader, 8)) return null
        val fragmentHeader = image.u64(mapHeader)
        val data = if (fragmentHeader and Header.FRAGMENT_INODE_FLAG != 0uL) {
            val offset = fragmentHeader xor Header.FRAGMENT_INODE_FLAG
            FragmentData(packed, offset, inode.size).takeIf(FragmentData::valid)
        } else CompressedData.open(image, header, inode, cache, packed)
        return data?.let { FileNode(inode.metadata, it) }
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
                val diskType = bytes[entryOffset + 10].toInt() and 0xff
                val type = if (diskType == 0) null else {
                    DiskFileType.fromDirectoryType(diskType)?.inodeType ?: return null
                }
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

    private sealed class Node(val metadata: InodeMetadata)

    private class DirectoryNode(metadata: InodeMetadata, val data: FlatData) : Node(metadata) {
        var cached: DirectoryData? = null
    }

    private class FileNode(metadata: InodeMetadata, val data: FileData) : Node(metadata)

    private class SymlinkNode(metadata: InodeMetadata, val target: VfsPathname) : Node(metadata)

    private class SpecialNode(metadata: InodeMetadata, val backend: InodeBackend) : Node(metadata)

    private data class Entry(
        val name: VfsName,
        val nid: ULong,
        val type: InodeType?,
    )

    private class DirectoryData(entries: List<Entry>) {
        val byName = entries.associateBy(Entry::name)
        val entries = entries.map {
            DirectoryEntry(it.name, InodeId(it.nid), it.type)
        }
    }

    private class ErofsDirectoryBackend(
        private val instance: ErofsInstance,
        private val superBlock: SuperBlock,
        private val node: DirectoryNode,
    ) : DirectoryBackend {
        override val type: InodeType = InodeType.DIRECTORY

        override fun lookup(
            caller: VfsOperationContext,
            directory: Inode,
            name: VfsName,
        ): VfsResult<DirectoryLookup> {
            val entry = instance.directoryData(node)?.byName?.get(name)
                ?: return VfsResult.Ok(DirectoryLookup(null))
            val inode = instance.inode(superBlock, entry.nid)
                ?: return VfsResult.Err(VfsError.IO)
            return VfsResult.Ok(DirectoryLookup(inode))
        }

        override fun open(
            caller: VfsOperationContext,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileBackend> {
            val entries = instance.directoryData(node)?.entries
                ?: return VfsResult.Err(VfsError.IO)
            return VfsResult.Ok(DirectoryHandle(entries))
        }
    }

    private class DirectoryHandle(private val entries: List<DirectoryEntry>) : OpenFileBackend {
        override fun iterate(
            caller: VfsOperationContext,
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

    private class RegularBackend(node: FileNode) : RegularFileBackend(), CachedFileBackend {
        private val data = node.data

        override fun open(
            caller: VfsOperationContext,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileBackend> =
            VfsResult.Ok(this)

        override fun read(offset: ULong, destination: ByteArray): Int {
            if (offset >= data.size) return 0
            val count = minOf(destination.size.toULong(), data.size - offset).toInt()
            return if (data.read(offset, destination, 0, count)) count else -1
        }
    }

    private class ErofsSymlinkBackend(
        private val target: VfsPathname,
    ) : SymlinkBackend {
        override val type: InodeType = InodeType.SYMLINK
        override fun readLink(
            caller: VfsOperationContext,
            inode: Inode,
            cachedOnly: Boolean,
        ): VfsResult<VfsPathname> = VfsResult.Ok(target)

        override fun open(
            caller: VfsOperationContext,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileBackend> =
            VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
    }
}
