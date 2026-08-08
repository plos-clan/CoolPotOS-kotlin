@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.memmap_request
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.utils.*

private data class MemoryRange(
    val base: ULong,
    val length: ULong,
) {
    val end: ULong
        get() = base + length

    val frameCount: ULong
        get() = length / PAGE_SIZE_BYTES
}

private data class MemmapDecision(
    val ranges: List<MemoryRange>,
    val totalsByType: Map<ULong, ULong>,
)

private enum class MemmapType(
    val id: ULong,
    val label: String,
    val allocationPriority: Int? = null,
) {
    USABLE(0uL, "USABLE", allocationPriority = 0),
    RESERVED(1uL, "RESERVED"),
    ACPI_RECLAIMABLE(2uL, "ACPI_RECLAIMABLE"),
    ACPI_NVS(3uL, "ACPI_NVS"),
    BAD_MEMORY(4uL, "BAD_MEMORY"),
    BOOTLOADER_RECLAIMABLE(5uL, "BOOTLOADER_RECLAIMABLE", allocationPriority = 1),
    EXECUTABLE_AND_MODULES(6uL, "EXECUTABLE_AND_MODULES"),
    FRAMEBUFFER(7uL, "FRAMEBUFFER"),
    ACPI_TABLES(8uL, "ACPI_TABLES");

    companion object {
        fun fromId(id: ULong): MemmapType? = entries.firstOrNull { it.id == id }
    }
}

object BuddyFrameAllocator {
    private const val MAX_BUDDY_ORDER = 30
    private const val PAGE_CACHE_ORDER = 8
    private const val PAGE_CACHE_CAPACITY = 1 shl PAGE_CACHE_ORDER
    private const val BYTES_PER_MIB = 1_048_576uL
    private const val LOW_MEMORY_GUARD = PAGE_SIZE_BYTES

    private val freeLists = Array(MAX_BUDDY_ORDER + 1) { linkedSetOf<ULong>() }
    private val pageCache = ULongArray(PAGE_CACHE_CAPACITY)
    private val lock = IrqSpinLock()

    private var cachedPages = 0
    private var usableFrames = 0uL
    private var initialized = false

    val isReady: Boolean
        get() = initialized

    fun initialize(): Boolean = lock.withLock { initializeLocked() }

    private fun initializeLocked(): Boolean {
        reset()

        val decision = analyzeMemmap() ?: run {
            println("Buddy: memmap response unavailable")
            return false
        }

        printMemmapSummary(decision.totalsByType)

        if (decision.ranges.isEmpty()) {
            println("Buddy: no allocatable memmap regions")
            return false
        }

        decision.ranges.forEach { range ->
            if (range.frameCount == 0uL) {
                return@forEach
            }
            addRange(range.base / PAGE_SIZE_BYTES, range.frameCount)
            usableFrames += range.frameCount
        }

        initialized = usableFrames != 0uL
        if (!initialized) {
            println("Buddy: initialization failed")
        }
        return initialized
    }

    fun allocateFrames(frameCount: ULong): ULong? = lock.withLock {
        if (frameCount == 0uL) {
            return@withLock null
        }
        if (!ensureInitializedLocked()) {
            return@withLock null
        }

        val targetOrder = requiredOrder(frameCount) ?: return@withLock null
        if (targetOrder == 0 && (cachedPages != 0 || refillPageCacheLocked())) {
            usableFrames--
            return@withLock pageCache[--cachedPages]
        }

        var sourceOrder = firstNonEmptyOrder(targetOrder)
        if (sourceOrder == null && cachedPages != 0) {
            drainPageCacheLocked()
            sourceOrder = firstNonEmptyOrder(targetOrder)
        }
        val allocatedOrder = sourceOrder ?: return@withLock null

        val blockStart = removeFirstBlock(allocatedOrder) ?: return@withLock null
        var order = allocatedOrder
        while (order > targetOrder) {
            order -= 1
            val buddyStart = blockStart + (1uL shl order)
            freeLists[order].add(buddyStart)
        }

        usableFrames -= 1uL shl targetOrder
        blockStart * PAGE_SIZE_BYTES
    }

    fun freeFrames(physicalAddress: ULong, frameCount: ULong): Boolean = lock.withLock {
        freeFramesLocked(physicalAddress, frameCount)
    }

    fun freeFrames(physicalAddresses: Iterable<ULong>): Boolean = lock.withLock {
        var succeeded = true
        physicalAddresses.forEach { address ->
            succeeded = freeFramesLocked(address, 1uL) && succeeded
        }
        succeeded
    }

    private fun freeFramesLocked(physicalAddress: ULong, frameCount: ULong): Boolean {
        if (frameCount == 0uL || !physicalAddress.isPageAligned()) {
            return false
        }
        if (!ensureInitializedLocked()) {
            return false
        }

        val requestedOrder = requiredOrder(frameCount) ?: return false
        if (requestedOrder == 0 && cachedPages < PAGE_CACHE_CAPACITY) {
            pageCache[cachedPages++] = physicalAddress
            usableFrames++
            return true
        }
        var order = requestedOrder
        var frameStart = physicalAddress / PAGE_SIZE_BYTES

        while (order < MAX_BUDDY_ORDER) {
            val buddyStart = frameStart xor (1uL shl order)
            if (!freeLists[order].remove(buddyStart)) {
                break
            }
            frameStart = minOf(frameStart, buddyStart)
            order += 1
        }

        freeLists[order].add(frameStart)
        usableFrames += 1uL shl requestedOrder
        return true
    }

    private fun analyzeMemmap(): MemmapDecision? {
        val response = memmap_request.response?.pointed ?: return null
        val entries = response.entries ?: return null
        val entryCount = response.entry_count.toInt()

        val totalsByType = mutableMapOf<ULong, ULong>()
        val rangesByPriority = mutableMapOf<Int, MutableList<MemoryRange>>()

        for (index in 0 until entryCount) {
            val entryPointer = entries[index] ?: continue
            val entry = entryPointer.pointed

            totalsByType[entry.type] = (totalsByType[entry.type] ?: 0uL) + entry.length

            val alignedRange = toAlignedRange(entry.base, entry.length) ?: continue
            val priority = MemmapType.fromId(entry.type)?.allocationPriority ?: continue
            rangesByPriority.getOrPut(priority) { mutableListOf() } += alignedRange
        }

        val selectedRanges = rangesByPriority.keys
            .minOrNull()
            ?.let { priority -> rangesByPriority.getValue(priority) }
            .orEmpty()
            .let(::mergeRanges)

        return MemmapDecision(
            ranges = selectedRanges,
            totalsByType = totalsByType,
        )
    }

    private fun toAlignedRange(base: ULong, length: ULong): MemoryRange? {
        val start = maxOf(base.alignUp(PAGE_SIZE_BYTES), LOW_MEMORY_GUARD)
        val end = (base + length).alignDown(PAGE_SIZE_BYTES)
        if (end <= start) {
            return null
        }
        return MemoryRange(base = start, length = end - start)
    }

    private fun mergeRanges(ranges: List<MemoryRange>): List<MemoryRange> {
        if (ranges.isEmpty()) {
            return emptyList()
        }

        val sorted = ranges.sortedBy { it.base }
        val merged = mutableListOf<MemoryRange>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            if (next.base <= current.end) {
                val mergedEnd = maxOf(current.end, next.end)
                current = MemoryRange(
                    base = current.base,
                    length = mergedEnd - current.base,
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun printMemmapSummary(totalsByType: Map<ULong, ULong>) {
        if (totalsByType.isEmpty()) {
            println("Buddy: memmap is empty")
            return
        }

        totalsByType.entries
            .sortedBy { it.key.toLong() }
            .forEach { (type, bytes) ->
                val mib = bytes / BYTES_PER_MIB
                println("Buddy: memmap ${memmapTypeName(type)} = ${mib} MiB (${bytes.hex()} bytes)")
            }
    }

    private fun memmapTypeName(type: ULong): String =
        MemmapType.fromId(type)?.label ?: "UNKNOWN($type)"

    private fun reset() {
        freeLists.forEach { it.clear() }
        cachedPages = 0
        usableFrames = 0uL
        initialized = false
    }

    private fun ensureInitializedLocked(): Boolean = initialized || initializeLocked()

    private fun addRange(startFrame: ULong, frameCount: ULong) {
        var currentFrame = startFrame
        var remainingFrames = frameCount

        while (remainingFrames > 0uL) {
            val order = largestOrderFor(currentFrame, remainingFrames)
            val blockFrames = 1uL shl order
            freeLists[order].add(currentFrame)
            currentFrame += blockFrames
            remainingFrames -= blockFrames
        }
    }

    private fun largestOrderFor(startFrame: ULong, frameCount: ULong): Int {
        var order = 0
        while (order < MAX_BUDDY_ORDER) {
            val nextOrder = order + 1
            val nextBlockFrames = 1uL shl nextOrder
            if (nextBlockFrames > frameCount) {
                break
            }
            if ((startFrame and (nextBlockFrames - 1uL)) != 0uL) {
                break
            }
            order = nextOrder
        }
        return order
    }

    private fun requiredOrder(frameCount: ULong): Int? {
        var order = 0
        var blockFrames = 1uL
        while (blockFrames < frameCount && order < MAX_BUDDY_ORDER) {
            order += 1
            blockFrames = blockFrames shl 1
        }
        return if (blockFrames >= frameCount) order else null
    }

    private fun firstNonEmptyOrder(order: Int): Int? =
        (order..MAX_BUDDY_ORDER).firstOrNull { freeLists[it].isNotEmpty() }

    private fun refillPageCacheLocked(): Boolean {
        val sourceOrder = firstNonEmptyOrder(PAGE_CACHE_ORDER) ?: return false
        val blockStart = removeFirstBlock(sourceOrder) ?: return false
        var order = sourceOrder
        while (order > PAGE_CACHE_ORDER) {
            order--
            freeLists[order].add(blockStart + (1uL shl order))
        }
        for (index in pageCache.indices) {
            pageCache[index] = (blockStart + index.toULong()) * PAGE_SIZE_BYTES
        }
        cachedPages = PAGE_CACHE_CAPACITY
        return true
    }

    private fun drainPageCacheLocked() {
        while (cachedPages != 0) {
            var frameStart = pageCache[--cachedPages] / PAGE_SIZE_BYTES
            var order = 0
            while (order < MAX_BUDDY_ORDER) {
                val buddyStart = frameStart xor (1uL shl order)
                if (!freeLists[order].remove(buddyStart)) break
                frameStart = minOf(frameStart, buddyStart)
                order++
            }
            freeLists[order].add(frameStart)
        }
    }

    private fun removeFirstBlock(order: Int): ULong? {
        val blockStart = freeLists[order].firstOrNull() ?: return null
        freeLists[order].remove(blockStart)
        return blockStart
    }
}
