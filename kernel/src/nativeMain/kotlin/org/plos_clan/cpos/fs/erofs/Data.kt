package org.plos_clan.cpos.fs.erofs

import org.plos_clan.cpos.mem.PreparedBufferDestination

internal class FlatData(
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

internal class PackedData private constructor(
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
