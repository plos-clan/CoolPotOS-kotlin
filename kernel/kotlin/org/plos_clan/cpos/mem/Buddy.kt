@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import natives.memmap_request
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.hex64
import org.plos_clan.cpos.utils.isPageAligned

private data class MemoryRange(
    val base: ULong,
    val length: ULong,
)

private data class MemmapDecision(
    val ranges: List<MemoryRange>,
    val totalsByType: Map<ULong, ULong>,
)

object BuddyFrameAllocator {
    private const val LIMINE_MEMMAP_USABLE = 0uL
    private const val LIMINE_MEMMAP_RESERVED = 1uL
    private const val LIMINE_MEMMAP_ACPI_RECLAIMABLE = 2uL
    private const val LIMINE_MEMMAP_ACPI_NVS = 3uL
    private const val LIMINE_MEMMAP_BAD_MEMORY = 4uL
    private const val LIMINE_MEMMAP_BOOTLOADER_RECLAIMABLE = 5uL
    private const val LIMINE_MEMMAP_EXECUTABLE_AND_MODULES = 6uL
    private const val LIMINE_MEMMAP_FRAMEBUFFER = 7uL
    private const val LIMINE_MEMMAP_ACPI_TABLES = 8uL

    private const val MAX_BUDDY_ORDER = 30
    private const val BYTES_PER_MIB = 1_048_576uL
    private const val LOW_MEMORY_GUARD = PAGE_SIZE_BYTES

    private val freeLists = Array(MAX_BUDDY_ORDER + 1) { linkedSetOf<ULong>() }

    private var originFrames = 0uL
    private var usableFrames = 0uL
    private var initialized = false

    val isReady: Boolean
        get() = initialized

    val availableFrames: ULong
        get() = usableFrames

    fun initialize(): Boolean {
        reset()

        val decision = analyzeMemmap()
        if (decision == null) {
            println("Buddy: memmap response unavailable")
            return false
        }

        printMemmapSummary(decision.totalsByType)

        if (decision.ranges.isEmpty()) {
            println("Buddy: no allocatable memmap regions")
            return false
        }

        decision.ranges.forEach { range ->
            val frameCount = range.length / PAGE_SIZE_BYTES
            if (frameCount == 0uL) {
                return@forEach
            }
            addRange(range.base / PAGE_SIZE_BYTES, frameCount)
            originFrames += frameCount
            usableFrames += frameCount
        }

        initialized = usableFrames != 0uL
        if (!initialized) {
            println("Buddy: initialization failed")
        }
        return initialized
    }

    fun allocateFrames(frameCount: ULong): ULong? {
        if (frameCount == 0uL) {
            return null
        }
        if (!initialized && !initialize()) {
            return null
        }

        val targetOrder = requiredOrder(frameCount) ?: return null
        val sourceOrder = firstNonEmptyOrder(targetOrder) ?: return null

        var blockStart = removeFirstBlock(sourceOrder) ?: return null
        var order = sourceOrder
        while (order > targetOrder) {
            order -= 1
            val buddyStart = blockStart + (1uL shl order)
            freeLists[order].add(buddyStart)
        }

        usableFrames -= 1uL shl targetOrder
        return blockStart * PAGE_SIZE_BYTES
    }

    fun freeFrames(physicalAddress: ULong, frameCount: ULong): Boolean {
        if (frameCount == 0uL || !physicalAddress.isPageAligned()) {
            return false
        }
        if (!initialized && !initialize()) {
            return false
        }

        val requestedOrder = requiredOrder(frameCount) ?: return false
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
        val usableRanges = mutableListOf<MemoryRange>()
        val reclaimableRanges = mutableListOf<MemoryRange>()

        for (index in 0 until entryCount) {
            val entryPointer = entries[index] ?: continue
            val entry = entryPointer.pointed

            totalsByType[entry.type] = (totalsByType[entry.type] ?: 0uL) + entry.length

            val alignedRange = toAlignedRange(entry.base, entry.length) ?: continue
            when (entry.type) {
                LIMINE_MEMMAP_USABLE -> usableRanges += alignedRange
                LIMINE_MEMMAP_BOOTLOADER_RECLAIMABLE -> reclaimableRanges += alignedRange
            }
        }

        val selectedRanges = when {
            usableRanges.isNotEmpty() -> {
                println("Buddy: using LIMINE_MEMMAP_USABLE")
                mergeRanges(usableRanges)
            }

            reclaimableRanges.isNotEmpty() -> {
                println("Buddy: usable unavailable, fallback to BOOTLOADER_RECLAIMABLE")
                mergeRanges(reclaimableRanges)
            }

            else -> emptyList()
        }

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
        return MemoryRange(
            base = start,
            length = end - start,
        )
    }

    private fun mergeRanges(ranges: List<MemoryRange>): List<MemoryRange> {
        if (ranges.isEmpty()) {
            return emptyList()
        }

        val sorted = ranges.sortedBy { it.base }
        val merged = mutableListOf<MemoryRange>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            val currentEnd = current.base + current.length
            val nextEnd = next.base + next.length
            if (next.base <= currentEnd) {
                val mergedEnd = maxOf(currentEnd, nextEnd)
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

        val sortedEntries = totalsByType.entries.sortedBy { it.key.toLong() }
        sortedEntries.forEach { entry ->
            val type = entry.key
            val bytes = entry.value
            val mib = bytes / BYTES_PER_MIB
            println("Buddy: memmap ${memmapTypeName(type)} = ${mib} MiB (0x${bytes.hex64()} bytes)")
        }
    }

    private fun memmapTypeName(type: ULong): String =
        when (type) {
            LIMINE_MEMMAP_USABLE -> "USABLE"
            LIMINE_MEMMAP_RESERVED -> "RESERVED"
            LIMINE_MEMMAP_ACPI_RECLAIMABLE -> "ACPI_RECLAIMABLE"
            LIMINE_MEMMAP_ACPI_NVS -> "ACPI_NVS"
            LIMINE_MEMMAP_BAD_MEMORY -> "BAD_MEMORY"
            LIMINE_MEMMAP_BOOTLOADER_RECLAIMABLE -> "BOOTLOADER_RECLAIMABLE"
            LIMINE_MEMMAP_EXECUTABLE_AND_MODULES -> "EXECUTABLE_AND_MODULES"
            LIMINE_MEMMAP_FRAMEBUFFER -> "FRAMEBUFFER"
            LIMINE_MEMMAP_ACPI_TABLES -> "ACPI_TABLES"
            else -> "UNKNOWN($type)"
        }

    private fun reset() {
        freeLists.forEach { it.clear() }
        originFrames = 0uL
        usableFrames = 0uL
        initialized = false
    }

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

    private fun removeFirstBlock(order: Int): ULong? {
        val blockStart = freeLists[order].firstOrNull() ?: return null
        freeLists[order].remove(blockStart)
        return blockStart
    }
}