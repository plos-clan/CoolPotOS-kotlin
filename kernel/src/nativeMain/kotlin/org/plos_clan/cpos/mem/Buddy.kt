@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.memmap_request
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isPageAligned

private const val BITS_PER_WORD = ULong.SIZE_BITS
private const val MAX_ZONE_ORDER = Int.SIZE_BITS - 2
internal const val INVALID_FRAME = ULong.MAX_VALUE

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

data class PhysicalMemoryStatistics(
    val totalBytes: ULong,
    val freeBytes: ULong,
)

internal interface FrameReclaimer {
    fun reclaim(target: ULong): ULong
}

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

private class OrderBitmap(blockCount: Int) {
    private val words = ULongArray(
        ((blockCount.toLong() + BITS_PER_WORD - 1) / BITS_PER_WORD).toInt(),
    )
    private var firstNonEmptyWord = words.size

    fun add(block: Int) {
        val wordIndex = block / BITS_PER_WORD
        val mask = 1uL shl (block % BITS_PER_WORD)
        words[wordIndex] = words[wordIndex] or mask
        if (wordIndex < firstNonEmptyWord) {
            firstNonEmptyWord = wordIndex
        }
    }

    fun remove(block: Int): Boolean {
        val wordIndex = block / BITS_PER_WORD
        val mask = 1uL shl (block % BITS_PER_WORD)
        val word = words[wordIndex]
        if (word and mask == 0uL) {
            return false
        }

        words[wordIndex] = word and mask.inv()
        if (wordIndex == firstNonEmptyWord && words[wordIndex] == 0uL) {
            advanceFirstNonEmptyWord()
        }
        return true
    }

    fun takeFirst(): Int {
        advanceFirstNonEmptyWord()
        if (firstNonEmptyWord == words.size) {
            return -1
        }

        val wordIndex = firstNonEmptyWord
        val word = words[wordIndex]
        val bit = word.countTrailingZeroBits()
        words[wordIndex] = word and (1uL shl bit).inv()
        if (words[wordIndex] == 0uL) {
            advanceFirstNonEmptyWord()
        }
        return wordIndex * BITS_PER_WORD + bit
    }

    private fun advanceFirstNonEmptyWord() {
        while (firstNonEmptyWord < words.size && words[firstNonEmptyWord] == 0uL) {
            firstNonEmptyWord++
        }
    }
}

private class BuddyZone(
    private val baseFrame: ULong,
    private val maxOrder: Int,
) {
    private val frameCount = 1 shl maxOrder
    private val freeBlocks = Array(maxOrder + 1) { order ->
        OrderBitmap(frameCount shr order)
    }
    private val allocatedBlocks = Array(maxOrder + 1) { order ->
        OrderBitmap(frameCount shr order)
    }

    init {
        freeBlocks[maxOrder].add(0)
    }

    fun allocate(order: Int): ULong {
        if (order > maxOrder) {
            return INVALID_FRAME
        }

        var sourceOrder = order
        var block = -1
        while (sourceOrder <= maxOrder) {
            block = freeBlocks[sourceOrder].takeFirst()
            if (block >= 0) {
                break
            }
            sourceOrder++
        }
        if (block < 0) {
            return INVALID_FRAME
        }

        while (sourceOrder > order) {
            sourceOrder--
            block *= 2
            freeBlocks[sourceOrder].add(block + 1)
        }

        val relativeFrame = block shl order
        allocatedBlocks[order].add(block)
        return (baseFrame + relativeFrame.toULong()) * PAGE_SIZE_BYTES
    }

    fun free(physicalAddress: ULong, order: Int): Boolean {
        if (physicalAddress < baseFrame * PAGE_SIZE_BYTES || order > maxOrder) {
            return false
        }

        val frame = physicalAddress / PAGE_SIZE_BYTES
        val relativeFrame = frame - baseFrame
        val blockFrames = 1uL shl order
        if (relativeFrame >= frameCount.toULong() ||
            relativeFrame and (blockFrames - 1uL) != 0uL ||
            blockFrames > frameCount.toULong() - relativeFrame
        ) {
            return false
        }

        var block = relativeFrame.toInt() shr order
        if (!allocatedBlocks[order].remove(block)) {
            return false
        }

        var mergedOrder = order
        while (mergedOrder < maxOrder) {
            val buddy = block xor 1
            if (!freeBlocks[mergedOrder].remove(buddy)) {
                break
            }
            block = block shr 1
            mergedOrder++
        }
        freeBlocks[mergedOrder].add(block)
        return true
    }
}

object BuddyFrameAllocator {
    private const val BYTES_PER_MIB = 1_048_576uL
    private const val LOW_MEMORY_GUARD = PAGE_SIZE_BYTES

    private val lock = IrqSpinLock()
    private var zones = emptyArray<BuddyZone>()
    private var managedFrames = 0uL
    private var usableFrames = 0uL
    private var initialized = false
    private var reclaimers = emptyArray<FrameReclaimer>()

    val isReady: Boolean
        get() = initialized

    fun initialize(): Boolean = lock.withLock { initialized || initializeLocked() }

    fun allocate(count: ULong): ULong {
        var address = allocateAvailable(count)
        if (address != INVALID_FRAME) return address

        val order = requiredOrder(count) ?: return INVALID_FRAME
        val target = 1uL shl order
        val installed = lock.withLock { reclaimers }
        for (reclaimer in installed) {
            do {
                val reclaimed = reclaimer.reclaim(target)
                if (reclaimed == 0uL) break
                address = allocateAvailable(count)
                if (address != INVALID_FRAME) return address
            } while (true)
        }
        return INVALID_FRAME
    }

    internal fun register(candidate: FrameReclaimer) {
        while (true) {
            val observed = lock.withLock {
                check(reclaimers.none { it === candidate }) {
                    "Frame reclaimer is already registered"
                }
                reclaimers
            }
            val updated = observed + candidate
            val installed = lock.withLock {
                if (reclaimers !== observed) return@withLock false
                reclaimers = updated
                true
            }
            if (installed) return
        }
    }

    private fun allocateAvailable(count: ULong): ULong = lock.withLock {
        if (count == 0uL || !initialized) {
            return@withLock INVALID_FRAME
        }

        val order = requiredOrder(count) ?: return@withLock INVALID_FRAME
        for (zone in zones) {
            val address = zone.allocate(order)
            if (address == INVALID_FRAME) continue
            usableFrames -= 1uL shl order
            return@withLock address
        }
        INVALID_FRAME
    }

    fun free(address: ULong, count: ULong): Boolean = lock.withLock {
        freeLocked(address, count)
    }

    fun free(addresses: List<ULong>): Boolean = lock.withLock {
        var succeeded = true
        var index = 0
        while (index < addresses.size) {
            succeeded = freeLocked(addresses[index], 1uL) && succeeded
            index++
        }
        succeeded
    }

    fun statistics(): PhysicalMemoryStatistics {
        var totalBytes = 0uL
        var freeBytes = 0uL
        lock.withLock {
            totalBytes = managedFrames * PAGE_SIZE_BYTES
            freeBytes = usableFrames * PAGE_SIZE_BYTES
        }
        return PhysicalMemoryStatistics(totalBytes, freeBytes)
    }

    private fun initializeLocked(): Boolean {
        reset()

        val decision = analyzeMemmap() ?: run {
            println("Buddy: memmap response unavailable")
            return false
        }
        printMemmapSummary(decision.totalsByType)

        val ranges = decision.ranges
        if (ranges.isEmpty()) {
            println("Buddy: no allocatable memmap regions")
            return false
        }

        zones = buildZones(ranges)
        ranges.forEach { range ->
            managedFrames += range.frameCount
        }
        usableFrames = managedFrames
        initialized = zones.isNotEmpty()
        if (!initialized) {
            println("Buddy: initialization failed")
        }
        return initialized
    }

    private fun freeLocked(address: ULong, count: ULong): Boolean {
        if (count == 0uL || !address.isPageAligned() || !initialized) {
            return false
        }

        val order = requiredOrder(count) ?: return false
        for (zone in zones) {
            if (!zone.free(address, order)) continue
            usableFrames += 1uL shl order
            return true
        }
        return false
    }

    private fun buildZones(ranges: List<MemoryRange>): Array<BuddyZone> {
        val result = mutableListOf<BuddyZone>()
        ranges.forEach { range ->
            var frame = range.base / PAGE_SIZE_BYTES
            var remainingFrames = range.frameCount
            while (remainingFrames > 0uL) {
                val order = largestZoneOrder(frame, remainingFrames)
                val zoneFrames = 1uL shl order
                result += BuddyZone(frame, order)
                frame += zoneFrames
                remainingFrames -= zoneFrames
            }
        }
        return result.toTypedArray()
    }

    private fun largestZoneOrder(frame: ULong, remainingFrames: ULong): Int {
        val sizeOrder = ULong.SIZE_BITS - 1 - remainingFrames.countLeadingZeroBits()
        val alignmentOrder = frame.countTrailingZeroBits()
        return minOf(MAX_ZONE_ORDER, sizeOrder, alignmentOrder)
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
        if (base > ULong.MAX_VALUE - (PAGE_SIZE_BYTES - 1uL)) {
            return null
        }
        val start = maxOf(base.alignUp(PAGE_SIZE_BYTES) ?: return null, LOW_MEMORY_GUARD)
        val end = (base + minOf(length, ULong.MAX_VALUE - base))
            .alignDown(PAGE_SIZE_BYTES)
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
                println("Buddy: memmap ${memmapTypeName(type)} = $mib MiB (${bytes.hex()} bytes)")
            }
    }

    private fun memmapTypeName(type: ULong): String =
        MemmapType.fromId(type)?.label ?: "UNKNOWN($type)"

    private fun reset() {
        zones = emptyArray()
        managedFrames = 0uL
        usableFrames = 0uL
        initialized = false
    }

    private fun requiredOrder(frameCount: ULong): Int? {
        var order = 0
        var blockFrames = 1uL
        while (blockFrames < frameCount && order < MAX_ZONE_ORDER) {
            order++
            blockFrames = blockFrames shl 1
        }
        return order.takeIf { blockFrames >= frameCount }
    }
}
