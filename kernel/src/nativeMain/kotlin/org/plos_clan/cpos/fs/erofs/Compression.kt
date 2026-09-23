@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fs.erofs

import bridge.cp_zstd_decompress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.alignUp

internal class CompactIndex private constructor(
    private val image: Image,
    private val blockSize: Int,
    private val base: ULong,
    private val count: Int,
    private val initialFourByteCount: Int,
    private val compactTwoByteCount: Int,
    private val algorithmTypes: Int,
    private val fragmentOffset: ULong?,
) {
    companion object {
        const val ADVISE_COMPACTED_2B = 0x0001
        const val ADVISE_BIG_PCLUSTER_1 = 0x0002
        const val ADVISE_BIG_PCLUSTER_2 = 0x0004
        const val SUPPORTED_ADVISE = ADVISE_COMPACTED_2B or
            ADVISE_BIG_PCLUSTER_1 or ADVISE_BIG_PCLUSTER_2
        const val ADVISE_FRAGMENT = 0x0020
        const val D0_COMPRESSED_BLOCKS = 1 shl 11
        const val ZSTD = 3

        fun open(image: Image, header: Header, inode: DiskInode): CompactIndex? {
            val mapHeader = (inode.location + inode.inodeSize.toULong()).alignUp(8uL) ?: return null
            if (!image.contains(mapHeader, 8)) return null
            val advise = image.u16(mapHeader + 4uL)
            val clusterBits = image.u8(mapHeader + 7uL)
            if (advise and ADVISE_FRAGMENT.inv() != SUPPORTED_ADVISE || clusterBits != 0) return null
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
                if (advise and ADVISE_FRAGMENT != 0) image.u32(mapHeader) else null,
            )
        }
    }

    fun extents(size: ULong, packed: FileData? = null): List<Extent>? {
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
                break
            }
            val previous = head
            if (previous != null) {
                result += previous.toExtent(start, blockSize, algorithmTypes) ?: return null
            }
            head = Head(logicalCluster, start, index.type, index.physicalBlock)
        }
        if (head != null && fragmentOffset != null) {
            val source = packed ?: return null
            val fragment = FragmentData(source, fragmentOffset, size - head.logicalStart)
            if (!fragment.valid()) return null
            result += Extent.Fragment(head.logicalStart, size, fragment)
        } else if (head != null) {
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
            return Extent.Stored(
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

internal sealed class Extent(val logicalStart: ULong, val logicalEnd: ULong) {
    abstract fun read(
        image: Image,
        cache: ExtentCache<Extent.Stored>,
        offset: ULong,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Boolean

    class Fragment(start: ULong, end: ULong, private val data: FileData) : Extent(start, end) {
        override fun read(
            image: Image,
            cache: ExtentCache<Extent.Stored>,
            offset: ULong,
            destination: ByteArray,
            destinationOffset: Int,
            count: Int,
        ): Boolean = data.read(offset - logicalStart, destination, destinationOffset, count)
    }

    class Stored(
        start: ULong,
        end: ULong,
        private val physicalOffset: ULong,
        val physicalBlocks: Int,
        private val blockSize: Int,
        private val compressed: Boolean,
    ) : Extent(start, end) {
        override fun read(
            image: Image,
            cache: ExtentCache<Extent.Stored>,
            offset: ULong,
            destination: ByteArray,
            destinationOffset: Int,
            count: Int,
        ): Boolean {
            val bytes = cache.getOrLoad(this) { load(image) } ?: return false
            val start = (offset - logicalStart).toInt()
            bytes.copyInto(destination, destinationOffset, start, start + count)
            return true
        }

        private fun load(image: Image): ByteArray? {
            val logicalSize = logicalEnd - logicalStart
            if (logicalSize > Int.MAX_VALUE.toULong()) return null
            val physicalSize = physicalBlocks * blockSize
            if (!image.contains(physicalOffset, physicalSize)) return null
            if (!compressed) return image.bytes(physicalOffset, logicalSize.toInt())

            val input = image.bytes(physicalOffset, physicalSize) ?: return null
            val source = input.indexOfFirst { it != 0.toByte() }
            if (source < 0 || source > input.size - 4) return null
            val destination = ByteArray(logicalSize.toInt())
            return input.usePinned { compressed ->
                destination.usePinned { output ->
                    val result = cp_zstd_decompress(
                        output.addressOf(0),
                        destination.size.toULong(),
                        compressed.addressOf(source),
                        (input.size - source).toULong(),
                    )
                    destination.takeIf { result == destination.size }
                }
            }
        }
    }
}

internal class ExtentCache<K : Any>(private val capacity: Int) {
    private class Entry {
        val loading = KernelMutex()
        var result: Result<ByteArray?>? = null
    }

    private val lock = IrqSpinLock()
    private val entries = linkedMapOf<K, Entry>()
    private var used = 0

    fun getOrLoad(key: K, load: () -> ByteArray?): ByteArray? {
        val entry = lock.withLock {
            val existing = entries.remove(key) ?: Entry()
            entries[key] = existing
            existing.result?.let { return it.getOrThrow() }
            existing
        }
        return entry.loading.withLock {
            entry.result?.let { return@withLock it.getOrThrow() }
            val result = runCatching(load)
            publish(key, entry, result)
            result.getOrThrow()
        }
    }

    private fun publish(key: K, entry: Entry, result: Result<ByteArray?>) = lock.withLock {
        entry.result = result
        entries.remove(key)
        val loaded = result.getOrNull() ?: return@withLock
        if (loaded.size > capacity) return@withLock
        val iterator = entries.iterator()
        while (used > capacity - loaded.size && iterator.hasNext()) {
            val evicted = iterator.next().value.result?.getOrNull() ?: continue
            used -= evicted.size
            iterator.remove()
        }
        entries[key] = entry
        used += loaded.size
    }

}
