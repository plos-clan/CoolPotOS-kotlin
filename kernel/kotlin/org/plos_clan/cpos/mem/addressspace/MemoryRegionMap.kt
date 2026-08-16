package org.plos_clan.cpos.mem

import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.isPageAligned

internal class MemoryRegionMap(
    private val allocationStart: ULong,
    private val allocationEnd: ULong,
    private val addressLimit: ULong,
) : Iterable<MemoryRegion> {
    private val entries = mutableListOf<MemoryRegion>()

    override fun iterator(): Iterator<MemoryRegion> = entries.iterator()

    val used: ULong
        get() = entries.fold(0uL) { total, region -> total + region.length }

    fun snapshot(): List<MemoryRegion> = List(entries.size) { entries[it].copy() }

    fun sharedRegions(): List<MemoryRegion> = entries.filter(MemoryRegion::shared)

    fun copyRetainedInto(target: MemoryRegionMap) {
        val retained = mutableListOf<MemoryRegionBacking>()
        for (region in entries) {
            val backing = region.backing ?: continue
            if (!backing.retain()) {
                retained.asReversed().forEach(MemoryRegionBacking::release)
                error("Memory region backing is unavailable")
            }
            retained += backing
        }
        target.entries += entries.map(MemoryRegion::copy)
    }

    fun releaseAll() {
        entries.forEach { it.backing?.release() }
        entries.clear()
    }

    fun insertCopies(source: List<MemoryRegion>): Boolean {
        if (source.isEmpty()) return true
        val additions = source.map(MemoryRegion::copy).sortedBy(MemoryRegion::start)
        if (additions.any { !valid(it) } ||
            additions.zipWithNext().any { (left, right) -> left.end > right.start } ||
            additions.any { intersection(it.start, it.end) != null }
        ) {
            return false
        }

        val retained = mutableListOf<MemoryRegionBacking>()
        for (addition in additions) {
            val backing = addition.backing ?: continue
            if (!backing.retain()) {
                retained.asReversed().forEach(MemoryRegionBacking::release)
                return false
            }
            retained += backing
        }
        additions.forEach { check(insertOwned(it)) }
        return true
    }

    fun insertOwned(region: MemoryRegion): Boolean {
        if (!valid(region) || intersection(region.start, region.end) != null) return false
        val index = entries.binarySearchBy(region.start, selector = MemoryRegion::start)
            .let { if (it < 0) -it - 1 else it }
        entries.add(index, region)
        return true
    }

    fun removeOwned(region: MemoryRegion): MemoryRegion? =
        entries.indexOfFirst { it === region }
            .takeIf { it >= 0 }
            ?.let(entries::removeAt)

    fun removeRange(start: ULong, end: ULong): List<MemoryRegion> {
        splitAt(start)
        splitAt(end)
        return buildList {
            val iterator = entries.listIterator(firstIntersectingIndex(start))
            while (iterator.hasNext()) {
                val region = iterator.next()
                if (region.start >= end) break
                add(region)
                iterator.remove()
            }
        }
    }

    fun find(address: ULong): MemoryRegion? {
        var low = 0
        var high = entries.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val region = entries[middle]
            when {
                address < region.start -> high = middle - 1
                address >= region.end -> low = middle + 1
                else -> return region
            }
        }
        return null
    }

    fun intersection(start: ULong, end: ULong): MemoryRegion? =
        entries.getOrNull(firstIntersectingIndex(start))?.takeIf { it.start < end }

    fun intersecting(start: ULong, end: ULong): List<MemoryRegion> = buildList {
        var index = firstIntersectingIndex(start)
        while (index < entries.size && entries[index].start < end) add(entries[index++])
    }

    fun fullyCovers(start: ULong, end: ULong): Boolean {
        var cursor = start
        var index = firstIntersectingIndex(start)
        while (cursor < end) {
            val region = entries.getOrNull(index++) ?: return false
            if (region.start > cursor || region.end <= cursor) return false
            cursor = minOf(region.end, end)
        }
        return true
    }

    fun splitAt(address: ULong) {
        val index = entries.indexOfFirst { address > it.start && address < it.end }
        if (index < 0) return
        val left = entries[index]
        check(left.backing?.retain() != false)
        val right = left.copy(
            start = address,
            offset = left.offset + (address - left.start),
        )
        left.end = address
        entries.add(index + 1, right)
    }

    fun mergeAround(target: MemoryRegion) {
        var index = entries.indexOfFirst { it === target }
        if (index < 0) return
        if (index > 0 && canMerge(entries[index - 1], entries[index])) {
            merge(index - 1)
            index--
        }
        while (index + 1 < entries.size && canMerge(entries[index], entries[index + 1])) {
            merge(index)
        }
    }

    fun validMmapRange(address: ULong, length: ULong): Boolean =
        address in allocationStart..<allocationEnd && length <= allocationEnd - address

    fun findUnmappedArea(hint: ULong, length: ULong): ULong? {
        val alignedHint = hint.alignDown(PAGE_SIZE_BYTES)
        if (validMmapRange(alignedHint, length) &&
            intersection(alignedHint, alignedHint + length) == null
        ) {
            return alignedHint
        }

        if (alignedHint >= allocationStart && alignedHint < allocationEnd - length) {
            findGap(alignedHint + length, allocationEnd, length)?.let { return it }
            findGap(allocationStart, alignedHint, length)?.let { return it }
        }
        return findGap(allocationStart, allocationEnd, length)
    }

    private fun merge(leftIndex: Int) {
        val left = entries[leftIndex]
        val right = entries.removeAt(leftIndex + 1)
        left.end = right.end
        right.backing?.release()
    }

    private fun canMerge(left: MemoryRegion, right: MemoryRegion): Boolean =
        left.end == right.start &&
            left.access == right.access &&
            left.maximumAccess == right.maximumAccess &&
            left.name == right.name &&
            left.type == right.type &&
            left.shared == right.shared &&
            left.sharedIdentity === right.sharedIdentity &&
            left.backing === right.backing &&
            ((left.type != MemoryRegionType.FILE && left.type != MemoryRegionType.MMIO) ||
                left.offset + left.length == right.offset)

    private fun firstIntersectingIndex(start: ULong): Int {
        var low = 0
        var high = entries.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (entries[middle].end <= start) low = middle + 1 else high = middle - 1
        }
        return low
    }

    private fun findGap(windowStart: ULong, windowEnd: ULong, length: ULong): ULong? {
        val start = windowStart.alignUp(PAGE_SIZE_BYTES) ?: return null
        val end = windowEnd.alignDown(PAGE_SIZE_BYTES)
        if (start >= end || length > end - start) return null

        var cursor = start
        var best: ULong? = null
        for (region in entries) {
            if (region.end <= cursor) continue
            if (region.start >= end) break
            val gapEnd = minOf(region.start, end)
            if (gapEnd > cursor && gapEnd - cursor >= length) best = gapEnd - length
            cursor = maxOf(cursor, region.end.alignUp(PAGE_SIZE_BYTES) ?: return best)
            if (cursor >= end) return best
        }
        return if (end - cursor >= length) end - length else best
    }

    private fun valid(region: MemoryRegion): Boolean =
        region.start < region.end &&
            region.start.isPageAligned() &&
            region.end.isPageAligned() &&
            (region.maximumAccess and MEMORY_REGION_ACCESS_MASK.inv()) == 0uL &&
            (region.access and region.maximumAccess.inv()) == 0uL &&
            region.end <= addressLimit
}
