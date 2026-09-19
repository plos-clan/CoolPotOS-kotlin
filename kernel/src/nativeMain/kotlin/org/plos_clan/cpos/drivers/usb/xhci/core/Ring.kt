@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.MmioAddress
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal class ProducerRing(memory: DmaMemory, minimumCapacity: Int = 1) {
    private val segmentCapacity = (PAGE_SIZE_BYTES / TRB_SIZE_BYTES.toULong()).toInt() - 1
    private val segments = mutableListOf<MmioRegion>()
    private val barrier = AtomicInt(0)
    val queue: RingQueue

    val capacity: Int
    val physicalAddress: ULong
        get() = segments.first().physicalAddress

    val pages: List<ULong>
        get() = segments.map { it.physicalAddress }

    val dequeuePointer: ULong
        get() = address(queue.enqueueIndex) or if (queue.cycleState) 1uL else 0uL

    init {
        require(minimumCapacity > 0)
        val count = (minimumCapacity.toLong() + segmentCapacity) / segmentCapacity
        require(count * segmentCapacity <= Int.MAX_VALUE)
        try {
            repeat(count.toInt()) { segments.add(memory.allocate()) }
        } catch (failure: Throwable) {
            segments.forEach { it.free() }
            throw failure
        }
        capacity = segments.size * segmentCapacity - 1
        queue = RingQueue(capacity + 1)
    }

    fun address(index: Int): ULong =
        segments[index / segmentCapacity].physicalAddress +
            (index % segmentCapacity).toULong() * TRB_SIZE_BYTES.toULong()

    fun indexOf(pointer: ULong, segment: Int): Int? {
        val offset = pointer - segments[segment].physicalAddress
        if (offset % TRB_SIZE_BYTES.toULong() != 0uL) return null
        val index = offset / TRB_SIZE_BYTES.toULong()
        return if (index < segmentCapacity.toULong()) segment * segmentCapacity + index.toInt()
        else null
    }

    fun enqueue(entry: RingEntry): Boolean {
        val firstCycle = queue.cycleState
        val enqueued =
            queue.enqueue(entry) { index, cycleState, trb, first ->
                val segment = index / segmentCapacity
                val position = index % segmentCapacity
                val words = segments[segment].view<UIntVar>()
                val offset = position * TRB_WORD_COUNT
                val cycle = if (cycleState) TRB_CYCLE else 0u
                words[offset] = trb.paramLow
                words[offset + 1] = trb.paramHigh
                words[offset + 2] = trb.status
                words[offset + 3] =
                    (trb.control and TRB_CYCLE.inv()) or if (first) cycle xor TRB_CYCLE else cycle
                if (position == segmentCapacity - 1) {
                    val last = segment == segments.lastIndex
                    val next = segments[if (last) 0 else segment + 1].physicalAddress
                    val link = segmentCapacity * TRB_WORD_COUNT
                    words[link] = next.toUInt()
                    words[link + 1] = (next shr 32).toUInt()
                    words[link + 2] = 0u
                    words[link + 3] =
                        (TRB_LINK shl 10) or
                            cycle or
                            (trb.control and TRB_CHAIN) or
                            if (last) TRB_ENT else 0u
                }
            }
        if (!enqueued) return false
        barrier.exchange(0)
        val words = segments[entry.first / segmentCapacity].view<UIntVar>()
        val control = entry.first % segmentCapacity * TRB_WORD_COUNT + 3
        words[control] =
            (entry.trbs.first().control and TRB_CYCLE.inv()) or if (firstCycle) TRB_CYCLE else 0u
        barrier.exchange(0)
        return true
    }

    fun free() {
        segments.forEach { it.free() }
    }
}

class EventRing(private val buffer: MmioRegion, private val erdpRegister: MmioAddress) {
    private val words = buffer.view<UIntVar>()
    private val barrier = AtomicInt(0)

    val physicalAddress = buffer.physicalAddress
    val capacity: Int = (PAGE_SIZE_BYTES / TRB_SIZE_BYTES.toULong()).toInt()
    private var dequeueIndex = 0
    private var cycleState = true

    fun pop(): Trb? {
        val offset = dequeueIndex * TRB_WORD_COUNT
        barrier.exchange(0)
        val control = words[offset + 3]
        if (control and TRB_CYCLE != if (cycleState) TRB_CYCLE else 0u) return null
        barrier.exchange(0)
        val event = Trb(words[offset], words[offset + 1], words[offset + 2], control)
        dequeueIndex++
        if (dequeueIndex == capacity) {
            dequeueIndex = 0
            cycleState = !cycleState
        }
        return event
    }

    fun updateErdp() {
        val physical = physicalAddress + dequeueIndex.toULong() * TRB_SIZE_BYTES.toULong()
        erdpRegister.writeSplitU64(physical, lowMask = 1u shl 3)
    }
}
