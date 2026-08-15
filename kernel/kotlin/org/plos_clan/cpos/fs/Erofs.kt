@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs

import bridge.cp_zstd_decompress
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.module.ModuleData
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.alignUp

private const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

data class ErofsOptions(
    val data: ModuleData,
    val cacheBytes: Int = DEFAULT_CACHE_BYTES,
) : FileSystemOptions

object Erofs : FileSystemType {
    override val name: String = "erofs"
    override val magic: ULong = 0xe0f5_e1e2uL
    override val requiresDevice: Boolean = true

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        val configuration = options as? ErofsOptions
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (configuration.cacheBytes < 0) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val instance = ErofsInstance.open(configuration.data, configuration.cacheBytes)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(SuperBlock(this, instance, instance::rootInode))
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

    fun rootInode(superBlock: SuperBlock): Inode =
        inode(superBlock, header.rootNid) ?: error("EROFS root inode is invalid")

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

private class FlatData(
    private val image: Image,
    private val blockSize: Int,
    private val inodeLocation: ULong,
    private val inodeSize: Int,
    private val rawBlock: ULong,
    private val size: ULong,
    private val inline: Boolean,
) {
    private val externalSize = if (inline && size != 0uL) {
        ((size + blockSize.toULong() - 1uL) / blockSize.toULong() - 1uL) * blockSize.toULong()
    } else {
        size
    }

    fun valid(): Boolean {
        if (externalSize != 0uL && !image.contains(rawBlock * blockSize.toULong(), externalSize)) {
            return false
        }
        val inlineSize = size - externalSize
        return inlineSize == 0uL || image.contains(
            inodeLocation + inodeSize.toULong(),
            inlineSize,
        )
    }

    fun readAll(): ByteArray? {
        if (size > Int.MAX_VALUE.toULong()) return null
        return ByteArray(size.toInt()).also {
            if (!read(0uL, it, 0, it.size)) return null
        }
    }

    private fun read(
        offset: ULong,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Boolean {
        var copied = 0
        while (copied < count) {
            val logical = offset + copied.toULong()
            val external = logical < externalSize
            val limit = if (external) externalSize else size
            val chunk = minOf(count - copied, (limit - logical).toInt())
            val physical = if (external) {
                rawBlock * blockSize.toULong() + logical
            } else {
                inodeLocation + inodeSize.toULong() + logical - externalSize
            }
            if (!image.copyInto(destination, destinationOffset + copied, physical, chunk)) return false
            copied += chunk
        }
        return true
    }
}

private class PackedData private constructor(
    private val image: Image,
    val size: ULong,
    private val extents: List<Extent>,
    cacheBytes: Int,
) {
    companion object {
        fun open(image: Image, header: Header, cacheBytes: Int): PackedData? {
            val inode = DiskInode.read(image, header, header.packedNid) ?: return null
            if (inode.layout != DataLayout.COMPRESSED_COMPACT ||
                inode.type != DiskFileType.REGULAR
            ) return null
            if (inode.size == 0uL) return PackedData(image, 0uL, emptyList(), cacheBytes)
            val decoder = CompactIndex.open(image, header, inode) ?: return null
            val extents = decoder.extents(inode.size) ?: return null
            if (extents.sumOf { it.physicalBlocks.toULong() } != inode.rawBlock) return null
            return PackedData(image, inode.size, extents, cacheBytes)
        }
    }

    private val cache = ExtentCache(cacheBytes)

    fun read(
        offset: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): Int? {
        if (count == 0) return 0
        if (offset >= size || count.toULong() > size - offset) return null
        var extentIndex = findExtent(offset)
        if (extentIndex < 0) return null
        var copied = 0
        while (copied < count) {
            val extent = extents[extentIndex]
            val data = cache.getOrLoad(extent.physicalOffset) { extent.load(image) } ?: return null
            val sourceOffset = (offset + copied.toULong() - extent.logicalStart).toInt()
            val chunk = minOf(count - copied, data.size - sourceOffset)
            if (chunk <= 0) return null
            val transferred = destination.copyFrom(
                destinationOffset + copied,
                data,
                sourceOffset,
                chunk,
            )
            copied += transferred
            if (transferred < chunk) return copied
            extentIndex++
        }
        return copied
    }

    private fun findExtent(offset: ULong): Int {
        var low = 0
        var high = extents.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val extent = extents[middle]
            when {
                offset < extent.logicalStart -> high = middle - 1
                offset >= extent.logicalEnd -> low = middle + 1
                else -> return middle
            }
        }
        return -1
    }
}

private class CompactIndex private constructor(
    private val image: Image,
    private val blockSize: Int,
    private val base: ULong,
    private val count: Int,
    private val initialFourByteCount: Int,
    private val compactTwoByteCount: Int,
    private val algorithmTypes: Int,
) {
    companion object {
        const val ADVISE_COMPACTED_2B = 0x0001
        const val ADVISE_BIG_PCLUSTER_1 = 0x0002
        const val ADVISE_BIG_PCLUSTER_2 = 0x0004
        const val SUPPORTED_ADVISE = ADVISE_COMPACTED_2B or
            ADVISE_BIG_PCLUSTER_1 or ADVISE_BIG_PCLUSTER_2
        const val D0_COMPRESSED_BLOCKS = 1 shl 11
        const val ZSTD = 3

        fun open(image: Image, header: Header, inode: DiskInode): CompactIndex? {
            val mapHeader = image.align(inode.location + inode.inodeSize.toULong(), 8) ?: return null
            if (!image.contains(mapHeader, 8)) return null
            val advise = image.u16(mapHeader + 4uL)
            val clusterBits = image.u8(mapHeader + 7uL)
            if (advise != SUPPORTED_ADVISE || clusterBits != 0) return null
            val count = ((inode.size + header.blockSize.toULong() - 1uL) /
                header.blockSize.toULong()).toInt()
            val base = mapHeader + 8uL
            var initial = (32 - (base.toInt() and 31)) / 4
            if (initial == 8) initial = 0
            val compact = if (initial < count) (count - initial) / 16 * 16 else 0
            return CompactIndex(
                image,
                header.blockSize,
                base,
                count,
                initial,
                compact,
                image.u8(mapHeader + 6uL),
            )
        }
    }

    fun extents(size: ULong): List<Extent>? {
        val result = mutableListOf<Extent>()
        var head: Head? = null
        for (logicalCluster in 0 until count) {
            val index = decode(logicalCluster) ?: return null
            if (head?.logicalCluster == logicalCluster - 1 && index.compressedBlocks != 0) {
                head.physicalBlocks = index.compressedBlocks
            }
            if (index.type == ClusterType.NONHEAD) continue
            val start = logicalCluster.toULong() * blockSize.toULong() + index.clusterOffset.toULong()
            if (head == null && start != 0uL) return null
            if (start >= size) {
                if (start != size) return null
                head?.toExtent(size, blockSize, algorithmTypes)?.let(result::add)
                    ?: return null
                head = null
                break
            }
            val previous = head
            if (previous != null) {
                result += previous.toExtent(start, blockSize, algorithmTypes) ?: return null
            }
            head = Head(logicalCluster, start, index.type, index.physicalBlock)
        }
        if (head != null) {
            head.toExtent(size, blockSize, algorithmTypes)?.let(result::add) ?: return null
        }
        return result.takeIf { it.isNotEmpty() && it.last().logicalEnd == size }
    }

    private fun decode(logicalCluster: Int): ClusterIndex? {
        var relative = logicalCluster
        var position = base
        val shift = when {
            relative < initialFourByteCount -> 2
            relative - initialFourByteCount < compactTwoByteCount -> {
                position += initialFourByteCount.toULong() * 4uL
                relative -= initialFourByteCount
                1
            }
            else -> {
                position += initialFourByteCount.toULong() * 4uL +
                    compactTwoByteCount.toULong() * 2uL
                relative -= initialFourByteCount + compactTwoByteCount
                2
            }
        }
        position += relative.toULong() shl shift
        val valuesPerPack = if (shift == 1) 16 else 2
        val packSize = valuesPerPack shl shift
        val packBase = position and (packSize.toULong() - 1uL).inv()
        val item = ((position - packBase).toInt()) shr shift
        val encodedBits = (packSize - 4) * 8 / valuesPerPack
        val value = compactValue(packBase, item, encodedBits) ?: return null
        val type = ClusterType.entries[(value shr 12) and 3]
        val low = value and 0xfff
        if (type == ClusterType.NONHEAD) {
            return ClusterIndex(
                type,
                blockSize,
                0uL,
                if (low and D0_COMPRESSED_BLOCKS != 0) low and D0_COMPRESSED_BLOCKS.inv() else 0,
            )
        }

        var cursor = item
        var physicalDelta = 0uL
        while (cursor > 0) {
            cursor--
            val previous = compactValue(packBase, cursor, encodedBits) ?: return null
            val previousType = ClusterType.entries[(previous shr 12) and 3]
            val previousLow = previous and 0xfff
            if (previousType != ClusterType.NONHEAD) {
                physicalDelta++
                continue
            }
            if (previousLow and D0_COMPRESSED_BLOCKS != 0) {
                cursor--
                physicalDelta += (previousLow and D0_COMPRESSED_BLOCKS.inv()).toULong()
                continue
            }
            if (previousLow <= 1) return null
            cursor -= previousLow - 2
        }
        val physicalBase = image.u32(packBase + packSize.toULong() - 4uL)
        return ClusterIndex(type, low, physicalBase + physicalDelta, 0)
    }

    private fun compactValue(packBase: ULong, index: Int, encodedBits: Int): Int? {
        val bit = index * encodedBits
        val offset = packBase + (bit / 8).toULong()
        if (!image.contains(offset, 4)) return null
        return ((image.u32(offset) shr (bit and 7)) and 0x3fffuL).toInt()
    }

    private enum class ClusterType {
        PLAIN,
        HEAD1,
        NONHEAD,
        HEAD2,
    }

    private data class ClusterIndex(
        val type: ClusterType,
        val clusterOffset: Int,
        val physicalBlock: ULong,
        val compressedBlocks: Int,
    )

    private class Head(
        val logicalCluster: Int,
        val logicalStart: ULong,
        val type: ClusterType,
        val physicalBlock: ULong,
        var physicalBlocks: Int = 1,
    ) {
        fun toExtent(end: ULong, blockSize: Int, algorithms: Int): Extent? {
            if (end <= logicalStart || physicalBlocks !in 1..Header.MAX_PCLUSTER_BLOCKS) return null
            val compressed = type != ClusterType.PLAIN
            val algorithm = if (type == ClusterType.HEAD2) algorithms shr 4 else algorithms and 0xf
            if (compressed && algorithm != ZSTD) return null
            val logicalSize = end - logicalStart
            if (logicalSize > Header.MAX_DECOMPRESSED_PCLUSTER.toULong() ||
                !compressed && logicalSize > blockSize.toULong()
            ) return null
            return Extent(
                logicalStart,
                end,
                physicalBlock * blockSize.toULong(),
                physicalBlocks,
                blockSize,
                compressed,
            )
        }
    }
}

private data class Extent(
    val logicalStart: ULong,
    val logicalEnd: ULong,
    val physicalOffset: ULong,
    val physicalBlocks: Int,
    private val blockSize: Int,
    private val compressed: Boolean,
) {
    fun load(image: Image): ByteArray? {
        val logicalSize = logicalEnd - logicalStart
        if (logicalSize > Int.MAX_VALUE.toULong()) return null
        val physicalSize = physicalBlocks * blockSize
        if (!image.contains(physicalOffset, physicalSize)) return null
        if (!compressed) return image.bytes(physicalOffset, logicalSize.toInt())

        val physicalEnd = physicalOffset + physicalSize.toULong()
        var source = physicalOffset
        while (source < physicalEnd && image.u8(source) == 0) source++
        if (source == physicalEnd || !image.contains(source, 4) ||
            image.u32(source) != Header.ZSTD_MAGIC
        ) return null
        val destination = ByteArray(logicalSize.toInt())
        return destination.usePinned { output ->
            val result = cp_zstd_decompress(
                output.addressOf(0),
                destination.size.toULong(),
                image.addressAt(source, (physicalEnd - source).toInt()),
                physicalEnd - source,
            )
            destination.takeIf { result == destination.size }
        }
    }
}

private class ExtentCache(private val capacity: Int) {
    private val lock = IrqSpinLock()
    private val entries = mutableMapOf<ULong, ByteArray>()
    private val order = ArrayDeque<ULong>()
    private var used = 0

    fun getOrLoad(key: ULong, load: () -> ByteArray?): ByteArray? {
        lock.withLock { entries[key] }?.let { return it }
        val loaded = load() ?: return null
        if (loaded.size > capacity) return loaded
        return lock.withLock {
            entries[key]?.let { return@withLock it }
            while (used > capacity - loaded.size && order.isNotEmpty()) {
                val evicted = entries.remove(order.removeFirst()) ?: continue
                used -= evicted.size
            }
            entries[key] = loaded
            order.addLast(key)
            used += loaded.size
            loaded
        }
    }
}

private data class DiskInode(
    val location: ULong,
    val inodeSize: Int,
    val layout: DataLayout,
    val type: DiskFileType,
    val size: ULong,
    val rawBlock: ULong,
    val metadata: InodeMetadata,
) {
    companion object {
        fun read(image: Image, header: Header, nid: ULong): DiskInode? {
            val location = header.inodeLocation(nid)
            if (!image.contains(location, Header.COMPACT_INODE_SIZE)) return null
            val format = image.u16(location)
            if (format and 0xfff0 != 0 || image.u16(location + 2uL) != 0) return null
            val layout = DataLayout.entries.getOrNull(format shr 1) ?: return null
            val extended = format and 1 != 0
            val inodeSize = if (extended) Header.EXTENDED_INODE_SIZE else Header.COMPACT_INODE_SIZE
            if (!image.contains(location, inodeSize)) return null
            val mode = image.u16(location + 4uL)
            val type = DiskFileType.fromMode(mode) ?: return null
            val size = if (extended) image.u64(location + 8uL) else image.u32(location + 8uL)
            val links = if (extended) image.u32(location + 44uL).toUInt() else {
                image.u16(location + 6uL).toUInt()
            }
            val uid = if (extended) image.u32(location + 24uL).toUInt() else {
                image.u16(location + 24uL).toUInt()
            }
            val gid = if (extended) image.u32(location + 28uL).toUInt() else {
                image.u16(location + 26uL).toUInt()
            }
            val metadata = InodeMetadata(
                mode = FileMode(mode.toUInt()),
                size = size,
                linkCount = links,
                uid = uid,
                gid = gid,
            )
            return DiskInode(
                location,
                inodeSize,
                layout,
                type,
                size,
                image.u32(location + 16uL),
                metadata,
            )
        }
    }
}

private enum class DataLayout {
    FLAT_PLAIN,
    COMPRESSED_FULL,
    FLAT_INLINE,
    COMPRESSED_COMPACT,
}

private enum class DiskFileType {
    REGULAR,
    DIRECTORY,
    SYMLINK;

    companion object {
        private const val MODE_TYPE_MASK = 0xf000
        private const val MODE_REGULAR = 0x8000
        private const val MODE_DIRECTORY = 0x4000
        private const val MODE_SYMLINK = 0xa000

        fun fromMode(mode: Int): DiskFileType? = when (mode and MODE_TYPE_MASK) {
            MODE_REGULAR -> REGULAR
            MODE_DIRECTORY -> DIRECTORY
            MODE_SYMLINK -> SYMLINK
            else -> null
        }
    }
}

private data class Header(
    val blockSize: Int,
    val rootNid: ULong,
    val packedNid: ULong,
    private val metadataStart: ULong,
) {
    companion object {
        const val COMPACT_INODE_SIZE = 32
        const val EXTENDED_INODE_SIZE = 64
        const val DIRENT_SIZE = 12
        const val MAX_NAME_LENGTH = 255
        const val MAX_PCLUSTER_BLOCKS = 256
        const val MAX_DECOMPRESSED_PCLUSTER = 12 * 1024 * 1024
        const val FRAGMENT_INODE_FLAG = 0x8000_0000_0000_0000uL
        const val ZSTD_MAGIC = 0xfd2f_b528uL

        private const val SUPER_OFFSET = 1024
        private const val SUPER_SIZE = 128
        private const val MAGIC = 0xe0f5_e1e2uL
        private const val FEATURE_COMPAT = 0x0000_0003uL
        private const val FEATURE_INCOMPAT = 0x0000_0023uL
        private const val ZSTD_ALGORITHM = 1 shl 3
        private const val ZSTD_CONFIG_SIZE = 6
        private const val ZSTD_WINDOW_LOG = 10

        fun read(image: Image): Header? {
            val offset = SUPER_OFFSET.toULong()
            if (!image.contains(offset, SUPER_SIZE) || image.u32(offset) != MAGIC ||
                image.u32(offset + 8uL) != FEATURE_COMPAT ||
                image.u8(offset + 12uL) != 12 || image.u8(offset + 13uL) != 0 ||
                image.u32(offset + 80uL) != FEATURE_INCOMPAT ||
                image.u16(offset + 84uL) != ZSTD_ALGORITHM ||
                image.u16(offset + 86uL) != 0 || image.u8(offset + 90uL) != 0 ||
                image.u8(offset + 91uL) != 0
            ) return null
            val blocks = image.u32(offset + 36uL)
            val blockSize = 1 shl image.u8(offset + 12uL)
            if (blocks * blockSize.toULong() != image.size.toULong()) return null
            val config = offset + SUPER_SIZE.toULong()
            if (!image.contains(config, 2 + ZSTD_CONFIG_SIZE) ||
                image.u16(config) != ZSTD_CONFIG_SIZE ||
                image.u8(config + 2uL) != 0 || image.u8(config + 3uL) != ZSTD_WINDOW_LOG
            ) return null
            val packedNid = image.u64(offset + 96uL)
            if (packedNid == 0uL) return null
            return Header(
                blockSize,
                image.u16(offset + 14uL).toULong(),
                packedNid,
                image.u32(offset + 40uL) * blockSize.toULong(),
            )
        }
    }

    fun inodeLocation(nid: ULong): ULong = metadataStart + nid * COMPACT_INODE_SIZE.toULong()
}

private class Image(private val data: ModuleData) {
    val size: Int
        get() = data.size

    fun contains(offset: ULong, count: Int): Boolean =
        count >= 0 && contains(offset, count.toULong())

    fun contains(offset: ULong, count: ULong): Boolean =
        offset <= size.toULong() && count <= size.toULong() - offset

    fun align(offset: ULong, alignment: Int): ULong? =
        offset.alignUp(alignment.toULong())

    fun u8(offset: ULong): Int = data[offset.toInt()].toInt() and 0xff

    fun u16(offset: ULong): Int = u8(offset) or (u8(offset + 1uL) shl 8)

    fun u32(offset: ULong): ULong = u16(offset).toULong() or (u16(offset + 2uL).toULong() shl 16)

    fun u64(offset: ULong): ULong = u32(offset) or (u32(offset + 4uL) shl 32)

    fun bytes(offset: ULong, count: Int): ByteArray? {
        if (!contains(offset, count)) return null
        return data.copyOfRange(offset.toInt(), offset.toInt() + count)
    }

    fun copyInto(
        destination: ByteArray,
        destinationOffset: Int,
        sourceOffset: ULong,
        count: Int,
    ): Boolean {
        if (!contains(sourceOffset, count) || destinationOffset < 0 ||
            destinationOffset > destination.size - count
        ) return false
        val target = checkNotNull(ByteArrayBuffer(destination).prepareWrite(destinationOffset, count))
        data.copyInto(target, destinationOffset, sourceOffset.toInt(), count)
        return true
    }

    fun addressAt(offset: ULong, count: Int) = data.addressAt(offset.toInt(), count)
}
