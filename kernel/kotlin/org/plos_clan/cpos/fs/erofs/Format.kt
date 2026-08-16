@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.module.ModuleData
import org.plos_clan.cpos.utils.alignUp

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

internal enum class DataLayout {
    FLAT_PLAIN,
    COMPRESSED_FULL,
    FLAT_INLINE,
    COMPRESSED_COMPACT,
}

internal enum class DiskFileType {
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

internal data class Header(
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

internal class Image(private val data: ModuleData) {
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
