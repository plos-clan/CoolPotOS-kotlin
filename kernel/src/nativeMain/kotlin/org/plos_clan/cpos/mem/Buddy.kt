@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem

import bridge.current_cpu_index
import bridge.memmap_request
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isPageAligned
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdateAt

private const val BITS_PER_WORD = ULong.SIZE_BITS
private const val MAX_ZONE_ORDER = Int.SIZE_BITS - 2
internal const val INVALID_FRAME = ULong.MAX_VALUE

internal data class MemoryRange(
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
    private val words = ULongArray((blockCount - 1) / BITS_PER_WORD + 1)
    private var first = words.size

    fun add(block: Int) {
        val index = block / BITS_PER_WORD
        words[index] = words[index] or (1uL shl block)
        first = minOf(first, index)
    }

    fun remove(block: Int): Boolean {
        val index = block / BITS_PER_WORD
        val mask = 1uL shl block
        val word = words[index]
        if (word and mask == 0uL) return false
        words[index] = word and mask.inv()
        return true
    }

    fun takeFirst(): Int {
        while (first < words.size) {
            val word = words[first]
            if (word != 0uL) {
                val bit = word.countTrailingZeroBits()
                words[first] = word and (word - 1uL)
                return first * BITS_PER_WORD + bit
            }
            first++
        }
        return -1
    }
}

internal class BuddyBlocks(val maxOrder: Int) {
    private val freeBlocks = Array(maxOrder + 1) { order ->
        OrderBitmap(1 shl (maxOrder - order))
    }

    init {
        freeBlocks[maxOrder].add(0)
    }

    fun take(order: Int): Int {
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
            return -1
        }

        while (sourceOrder > order) {
            sourceOrder--
            block *= 2
            freeBlocks[sourceOrder].add(block + 1)
        }

        return block shl order
    }

    fun put(offset: Int, order: Int) {
        var block = offset shr order
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
    }
}

private class BuddyZone(
    private val baseFrame: ULong,
    private val maxOrder: Int,
) {
    private val frameCount = 1 shl maxOrder
    private val blocks = BuddyBlocks(maxOrder)
    private val allocated = AtomicLongArray((frameCount - 1) / (BITS_PER_WORD / 2) + 1)
    private val sectionSize = minOf(frameCount, (PAGE_SIZE_BYTES / Int.SIZE_BYTES.toUInt()).toInt())
    private val sections = AtomicArray<AtomicIntArray?>(frameCount / sectionSize) { null }

    fun references(frame: ULong): AtomicIntArray {
        val index = (frame - baseFrame).toInt() / sectionSize
        sections.loadAt(index)?.let { return it }
        val counts = AtomicIntArray(sectionSize)
        return sections.compareAndExchangeAt(index, null, counts) ?: counts
    }

    fun compareFrame(frame: ULong): Int = when {
        frame < baseFrame -> 1
        frame - baseFrame >= frameCount.toUInt() -> -1
        else -> 0
    }

    fun mark(address: ULong, order: Int, inUse: Boolean): Boolean {
        if (order > maxOrder) return false
        val frame = (address / PAGE_SIZE_BYTES - baseFrame).toInt()
        if (frame and ((1 shl order) - 1) != 0) return false
        val node = (frameCount shr order) + (frame shr order)
        val mask = 1L shl node
        val previous = allocated.fetchAndUpdateAt(node / BITS_PER_WORD) { bits ->
            if (inUse) bits or mask else bits and mask.inv()
        }
        return (previous and mask == 0L) == inUse
    }

    fun allocate(order: Int): ULong {
        val offset = blocks.take(order)
        if (offset < 0) return INVALID_FRAME
        return (baseFrame + offset.toUInt()) * PAGE_SIZE_BYTES
    }

    fun recycle(address: ULong, order: Int) {
        val offset = address / PAGE_SIZE_BYTES - baseFrame
        blocks.put(offset.toInt(), order)
    }
}

internal fun interface CpuIndex {
    fun current(): Int
}

internal class BuddyPool(
    ranges: List<MemoryRange>,
    processors: Int,
    private val cpu: CpuIndex,
) {
    private val lock = IrqSpinLock()
    private val zones = buildZones(ranges)
    private val managedFrames = ranges.sumOf { it.frameCount }
    @Volatile var globalFreeFrames = managedFrames
        private set
    private val reclaimers = AtomicReference(emptyArray<FrameReclaimer>())
    private val caches = Array(processors) { LocalPages() }

    private companion object {
        const val BATCH_SIZE = 32
    }

    private inner class LocalPages : FrameReclaimer {
        private val lock = IrqSpinLock()
        private val frames = ULongArray(2 * BATCH_SIZE)
        @Volatile var size = 0
            private set

        fun take(): ULong = lock.withLock {
            if (size == 0) refill()
            if (size == 0) INVALID_FRAME else frames[--size]
        }

        fun put(address: ULong) = lock.withLock {
            if (size == frames.size) drain(BATCH_SIZE)
            frames[size++] = address
        }

        override fun reclaim(target: ULong): ULong = lock.withLock {
            val count = minOf(target, size.toULong()).toInt()
            if (count > 0) drain(count)
            count.toULong()
        }

        private fun refill() = this@BuddyPool.lock.withLock {
            for (zone in zones) {
                while (size < BATCH_SIZE) {
                    val address = zone.allocate(0)
                    if (address == INVALID_FRAME) break
                    frames[size++] = address
                }
                if (size == BATCH_SIZE) break
            }
            globalFreeFrames -= size.toUInt()
        }

        private fun drain(count: Int) = this@BuddyPool.lock.withLock {
            repeat(count) {
                val address = frames[--size]
                val zone = checkNotNull(zoneOf(address))
                zone.recycle(address, 0)
            }
            globalFreeFrames += count.toUInt()
        }
    }

    fun allocate(count: ULong, reclaim: Boolean = true): ULong {
        val order = requiredOrder(count)
        if (order !in 0..MAX_ZONE_ORDER) return INVALID_FRAME
        val local = if (order == 0) caches.getOrNull(cpu.current()) else null
        var address = local?.take() ?: lock.withLock { allocateLocked(order) }
        if (address == INVALID_FRAME && reclaim) address = allocateUnderPressure(order)
        if (address != INVALID_FRAME) {
            val zone = checkNotNull(zoneOf(address))
            check(zone.mark(address, order, true))
        }
        return address
    }

    private fun allocateUnderPressure(order: Int): ULong {
        val target = 1uL shl order
        var address = drainAndAllocate(order)
        if (address != INVALID_FRAME) return address
        for (reclaimer in reclaimers.load()) {
            while (reclaimer.reclaim(target) > 0uL) {
                address = drainAndAllocate(order)
                if (address != INVALID_FRAME) return address
            }
        }
        return INVALID_FRAME
    }

    private fun drainAndAllocate(order: Int): ULong {
        for (cache in caches) cache.reclaim(ULong.MAX_VALUE)
        return lock.withLock { allocateLocked(order) }
    }

    internal fun register(candidate: FrameReclaimer) {
        do {
            val observed = reclaimers.load()
            check(observed.none { it === candidate })
            val updated = observed + candidate
        } while (!reclaimers.compareAndSet(observed, updated))
    }

    private fun allocateLocked(order: Int): ULong {
        for (zone in zones) {
            val address = zone.allocate(order)
            if (address == INVALID_FRAME) continue
            globalFreeFrames -= 1uL shl order
            return address
        }
        return INVALID_FRAME
    }

    fun free(address: ULong, count: ULong): Boolean {
        val order = requiredOrder(count)
        if (!address.isPageAligned() || order !in 0..MAX_ZONE_ORDER) return false
        val zone = zoneOf(address) ?: return false
        if (!zone.mark(address, order, false)) return false
        val local = if (order == 0) caches.getOrNull(cpu.current()) else null
        if (local != null) local.put(address)
        else lock.withLock {
            zone.recycle(address, order)
            globalFreeFrames += 1uL shl order
        }
        return true
    }

    internal fun references(address: ULong): AtomicIntArray =
        checkNotNull(zoneOf(address)).references(address / PAGE_SIZE_BYTES)

    private fun zoneOf(address: ULong): BuddyZone? {
        val frame = address / PAGE_SIZE_BYTES
        var low = 0
        var high = zones.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val zone = zones[middle]
            when (zone.compareFrame(frame)) {
                -1 -> low = middle + 1
                1 -> high = middle - 1
                else -> return zone
            }
        }
        return null
    }

    fun statistics(): PhysicalMemoryStatistics {
        var totalBytes = 0uL
        var freeBytes = 0uL
        lock.withLock {
            totalBytes = managedFrames * PAGE_SIZE_BYTES
            val cached = caches.sumOf { it.size.toULong() }
            freeBytes = (globalFreeFrames + cached) * PAGE_SIZE_BYTES
        }
        return PhysicalMemoryStatistics(totalBytes, freeBytes)
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

    private fun requiredOrder(count: ULong): Int =
        if (count == 0uL) -1 else ULong.SIZE_BITS - (count - 1uL).countLeadingZeroBits()
}

object BuddyFrameAllocator {
    private const val BYTES_PER_MIB = 1_048_576uL
    private const val LOW_MEMORY_GUARD = PAGE_SIZE_BYTES
    private val lock = IrqSpinLock()
    @Volatile private var active: BuddyPool? = null

    val isReady: Boolean get() = active != null
    internal val globalFreeFrames get() = active?.globalFreeFrames ?: 0uL

    fun initialize(): Boolean = lock.withLock {
        if (active != null) return@withLock true
        val decision = analyzeMemmap() ?: return@withLock false
        printMemmapSummary(decision.totalsByType)
        if (decision.ranges.isEmpty()) return@withLock false
        val processors = bridge.mp_request.response?.pointed?.cpu_count ?: 1uL
        active = BuddyPool(decision.ranges, processors.toInt(), ::current_cpu_index)
        true
    }

    fun allocate(count: ULong, reclaim: Boolean = true): ULong {
        val pool = active ?: return INVALID_FRAME
        return pool.allocate(count, reclaim)
    }

    fun free(address: ULong, count: ULong): Boolean {
        val pool = active ?: return false
        return pool.free(address, count)
    }

    internal fun register(candidate: FrameReclaimer) = checkNotNull(active).register(candidate)

    internal fun references(address: ULong): AtomicIntArray = checkNotNull(active).references(address)

    fun statistics(): PhysicalMemoryStatistics =
        active?.statistics() ?: PhysicalMemoryStatistics(0uL, 0uL)

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

}
