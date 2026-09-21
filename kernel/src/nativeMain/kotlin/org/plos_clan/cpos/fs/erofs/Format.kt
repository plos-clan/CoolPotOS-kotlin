package org.plos_clan.cpos.fs.erofs

import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.time.Instant
import org.plos_clan.cpos.block.ByteSource
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal data class DiskInode(
    val location: ULong,
    val inodeSize: Int,
    val layout: DataLayout,
    val type: DiskFileType,
    val size: ULong,
    val rawBlock: ULong,
    val metadata: InodeMetadata,
) {
    companion object {
        private const val PERMISSION_MASK = 0x0fffu

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
            val rawBlock = image.u32(location + 16uL)
            val deviceNumber = if (type == DiskFileType.CHARACTER_DEVICE ||
                type == DiskFileType.BLOCK_DEVICE
            ) {
                rawBlock.takeIf { DeviceNumber.fromEncoded(it) != null } ?: return null
            } else {
                0uL
            }
            val links = if (extended) image.u32(location + 44uL).toUInt() else {
                image.u16(location + 6uL).toUInt()
            }
            val uid = if (extended) image.u32(location + 24uL).toUInt() else {
                image.u16(location + 24uL).toUInt()
            }
            val gid = if (extended) image.u32(location + 28uL).toUInt() else {
                image.u16(location + 26uL).toUInt()
            }
            val modificationTime = if (extended) {
                val nanoseconds = image.u32(location + 40uL)
                if (nanoseconds >= Instant.NANOSECONDS_PER_SECOND) return null
                Instant(image.u64(location + 32uL).toLong(), nanoseconds.toUInt())
            } else {
                header.buildTime
            }
            val metadata = InodeMetadata(
                mode = FileMode(mode.toUInt() and PERMISSION_MASK),
                size = size,
                linkCount = links,
                deviceNumber = deviceNumber,
                uid = uid,
                gid = gid,
                timestamps = InodeTimestamps.fromModificationTime(modificationTime),
            )
            return DiskInode(
                location,
                inodeSize,
                layout,
                type,
                size,
                rawBlock,
                metadata,
            )
        }
    }
}

internal enum class DataLayout {
    FLAT_PLAIN,
    COMPRESSED_FULL,
    FLAT_INLINE,
    COMPRESSED_COMPACT,
}

internal enum class DiskFileType(
    val inodeType: InodeType,
    private val mode: Int,
    private val directoryType: Int,
) {
    REGULAR(InodeType.REGULAR, 0x8000, 1),
    DIRECTORY(InodeType.DIRECTORY, 0x4000, 2),
    CHARACTER_DEVICE(InodeType.CHARACTER_DEVICE, 0x2000, 3),
    BLOCK_DEVICE(InodeType.BLOCK_DEVICE, 0x6000, 4),
    PIPE(InodeType.PIPE, 0x1000, 5),
    SOCKET(InodeType.SOCKET, 0xc000, 6),
    SYMLINK(InodeType.SYMLINK, 0xa000, 7),
    ;

    companion object {
        private const val MODE_TYPE_MASK = 0xf000

        fun fromMode(mode: Int): DiskFileType? =
            entries.firstOrNull { it.mode == (mode and MODE_TYPE_MASK) }

        fun fromDirectoryType(type: Int): DiskFileType? =
            entries.firstOrNull { it.directoryType == type }
    }
}

internal data class Header(
    val blockSize: Int,
    val rootNid: ULong,
    val packedNid: ULong,
    val buildTime: Instant,
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

        private const val SUPER_OFFSET = 1024
        private const val SUPER_SIZE = 128
        private const val MAGIC = 0xe0f5_e1e2uL
        private const val FEATURE_COMPAT = 0x0000_0003uL
        private const val FEATURE_INCOMPAT = 0x0000_0023uL
        private const val ZSTD_ALGORITHM = 1 shl 3
        private const val ZSTD_CONFIG_SIZE = 6
        private const val ZSTD_MAX_WINDOW_LOG = 21

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
            val length = blocks * blockSize.toULong()
            if (length < blockSize.toULong() || !image.restrict(length)) return null
            val config = offset + SUPER_SIZE.toULong()
            if (!image.contains(config, 2 + ZSTD_CONFIG_SIZE) ||
                image.u16(config) != ZSTD_CONFIG_SIZE ||
                image.u8(config + 2uL) != 0 || image.u8(config + 3uL) > ZSTD_MAX_WINDOW_LOG
            ) return null
            val packedNid = image.u64(offset + 96uL)
            if (packedNid == 0uL) return null
            val buildTimeNanoseconds = image.u32(offset + 32uL)
            if (buildTimeNanoseconds >= Instant.NANOSECONDS_PER_SECOND) return null
            return Header(
                blockSize,
                image.u16(offset + 14uL).toULong(),
                packedNid,
                Instant(image.u64(offset + 24uL).toLong(), buildTimeNanoseconds.toUInt()),
                image.u32(offset + 40uL) * blockSize.toULong(),
            )
        }
    }

    fun inodeLocation(nid: ULong): ULong = metadataStart + nid * COMPACT_INODE_SIZE.toULong()
}

internal class Image(private val data: ByteSource) : AutoCloseable {
    var size: ULong = data.size
        private set

    private val lock = KernelMutex()
    private var pageOffset = ULong.MAX_VALUE
    private var page = ByteArray(0)

    override fun close() = data.close()

    fun restrict(length: ULong): Boolean {
        if (length > size) return false
        size = length
        return true
    }

    fun contains(offset: ULong, count: Int): Boolean =
        count >= 0 && contains(offset, count.toULong())

    fun contains(offset: ULong, count: ULong): Boolean =
        offset <= size && count <= size - offset

    fun u8(offset: ULong): Int = scalar(offset, 1).toInt()
    fun u16(offset: ULong): Int = scalar(offset, 2).toInt()
    fun u32(offset: ULong): ULong = scalar(offset, 4)
    fun u64(offset: ULong): ULong = scalar(offset, 8)

    private fun scalar(offset: ULong, width: Int): ULong = lock.withLock {
        check(contains(offset, width)) { "EROFS field outside image" }
        var result = 0uL
        for (index in 0 until width) {
            val position = offset + index.toUInt()
            val base = position - position % PAGE_SIZE_BYTES
            if (base != pageOffset) {
                val loaded = ByteArray(minOf(PAGE_SIZE_BYTES, size - base).toInt())
                check(data.read(base, loaded)) { "EROFS read failed" }
                page = loaded
                pageOffset = base
            }
            result = result or ((page[(position - base).toInt()].toULong() and 255uL) shl (index * 8))
        }
        result
    }

    fun bytes(offset: ULong, count: Int): ByteArray? {
        if (!contains(offset, count)) return null
        return ByteArray(count).takeIf { data.read(offset, it) }
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
        if (destinationOffset == 0 && count == destination.size) return data.read(sourceOffset, destination)
        val loaded = bytes(sourceOffset, count) ?: return false
        loaded.copyInto(destination, destinationOffset)
        return true
    }
}
