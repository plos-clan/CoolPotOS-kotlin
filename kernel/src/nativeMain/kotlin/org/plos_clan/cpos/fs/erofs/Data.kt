package org.plos_clan.cpos.fs.erofs

internal interface FileData {
    val size: ULong

    fun read(offset: ULong, destination: ByteArray, destinationOffset: Int, count: Int): Boolean
}

internal class FragmentData(
    private val source: FileData,
    private val offset: ULong,
    override val size: ULong,
) : FileData {
    fun valid(): Boolean = offset <= source.size && size <= source.size - offset

    override fun read(offset: ULong, destination: ByteArray, destinationOffset: Int, count: Int): Boolean =
        offset <= size && count >= 0 && count.toULong() <= size - offset &&
            source.read(this.offset + offset, destination, destinationOffset, count)
}

internal class FlatData(
    private val image: Image,
    private val blockSize: Int,
    private val inodeLocation: ULong,
    private val inodeSize: Int,
    private val rawBlock: ULong,
    override val size: ULong,
    inline: Boolean,
) : FileData {
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

    override fun read(
        offset: ULong,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Boolean {
        if (offset > size || count < 0 || count.toULong() > size - offset) return false
        var copied = 0
        while (copied < count) {
            val logical = offset + copied.toULong()
            val external = logical < externalSize
            val limit = if (external) externalSize else size
            val available = limit - logical
            val chunk = minOf((count - copied).toULong(), available).toInt()
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

internal class CompressedData private constructor(
    private val image: Image,
    override val size: ULong,
    private val extents: List<Extent>,
    private val cache: ExtentCache<Extent.Stored>,
) : FileData {
    companion object {
        fun open(
            image: Image,
            header: Header,
            inode: DiskInode,
            cache: ExtentCache<Extent.Stored>,
            packed: FileData? = null,
        ): CompressedData? {
            if (inode.layout != DataLayout.COMPRESSED_COMPACT ||
                inode.type != DiskFileType.REGULAR
            ) return null
            if (inode.size == 0uL) return CompressedData(image, 0uL, emptyList(), cache)
            val decoder = CompactIndex.open(image, header, inode) ?: return null
            val extents = decoder.extents(inode.size, packed) ?: return null
            val blocks = extents.sumOf { if (it is Extent.Stored) it.physicalBlocks.toULong() else 0uL }
            if (blocks != inode.rawBlock) return null
            return CompressedData(image, inode.size, extents, cache)
        }
    }

    override fun read(
        offset: ULong,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Boolean {
        if (count == 0) return true
        if (offset >= size || count.toULong() > size - offset) return false
        var extentIndex = findExtent(offset)
        if (extentIndex < 0) return false
        var copied = 0
        while (copied < count) {
            val extent = extents[extentIndex]
            val position = offset + copied.toULong()
            val available = extent.logicalEnd - position
            val chunk = minOf((count - copied).toULong(), available).toInt()
            if (chunk <= 0) return false
            if (!extent.read(image, cache, position, destination, destinationOffset + copied, chunk)) return false
            copied += chunk
            extentIndex++
        }
        return true
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
