@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.set
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.concurrent.Volatile

class Endpoint
internal constructor(
    private val controller: Xhci,
    private val slot: Slot,
    private val dci: Int,
    var packetSize: UInt,
    val streamCount: Int = 0,
) {
    private enum class State {
        READY,
        HALTED,
        PAUSED,
        CLOSED,
    }

    private class Queue(memory: DmaMemory) {
        var ring = ProducerRing(memory)
        val pending = mutableSetOf<PendingTransfer>()
        val space = RingSpace()
        var minimumCapacity = 0
    }

    private val mutex = Mutex()
    private val queues = mutableListOf<Queue>()
    private val pages = mutableMapOf<ULong, Pair<Queue, Int>>()
    private var streamContexts: MmioRegion? = null
    @Volatile private var state = State.READY

    val contextEntries: Int
        get() =
            StreamAllocation(streamCount, streamCount, controller.capability.maxStreamContexts)
                .contextEntries

    val dequeuePointer: ULong
        get() = streamContexts?.physicalAddress ?: queues[0].ring.dequeuePointer

    private val hardwareState: UInt
        get() =
            slot.outContext?.let { EndpointContext(it, dci, controller.contextSize, false).state }
                ?: 0u

    init {
        try {
            if (streamCount != 0) {
                val size = contextEntries.toULong() * 16uL
                streamContexts =
                    controller.memory.allocate((size + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES)
            }
            repeat(if (streamCount == 0) 1 else streamCount) {
                queues.add(Queue(controller.memory))
            }
            queues.forEachIndexed { index, queue ->
                queue.ring.pages.forEachIndexed { segment, page -> pages[page] = queue to segment }
                streamContexts
                    ?.view<ULongVar>()
                    ?.set((index + 1) * 2, queue.ring.dequeuePointer or 2uL)
            }
        } catch (failure: Throwable) {
            free()
            throw failure
        }
    }

    suspend fun submit(request: UsbTransfer): Boolean {
        val stream = request.streamId.toInt()
        val index = if (streamCount == 0) 0 else stream - 1
        if (streamCount == 0 && stream != 0 || (request.setup != null) != (dci == 1)) return false
        val queue = queues.getOrNull(index) ?: return false
        if (!slot.active || request.isCompleted) return false
        if (state == State.CLOSED || state == State.PAUSED) return false
        val pending = try {
            PendingTransfer(request, controller.memory, packetSize)
        } catch (_: DmaMemory.AllocationFailure) {
            return false
        }
        var submitted = false
        return try {
            val entry = transferEntry(queue, pending)
            submitted = enqueue(queue, entry, pending, stream)
            submitted
        } finally {
            if (!submitted) pending.free()
        }
    }

    private suspend fun enqueue(
        queue: Queue,
        entry: RingEntry,
        pending: PendingTransfer,
        stream: Int,
    ): Boolean {
        while (true) {
            val waiting = mutex.withLock {
                if (state == State.HALTED && dci == 1) {
                    val reset = withContext(NonCancellable) { reset(TransferStatus.CANCELLED) }
                    if (!reset) return false
                }
                if (state != State.READY || !slot.active) return false
                val capacity = maxOf(queue.minimumCapacity, entry.trbs.size)
                if (capacity > queue.ring.capacity) {
                    val drain = drainBeforeGrowth(queue, capacity)
                    if (drain != null) return@withLock drain
                    if (state != State.READY || !grow(queue, capacity, stream)) return false
                }
                controller.eventLock.withLock submit@{
                    if (state != State.READY || !slot.active) return false
                    if (!queue.ring.enqueue(entry)) return@submit queue.space.nextChange()
                    queue.pending.add(pending)
                    controller.doorbell.ring(slot.id, dci.toUInt(), stream)
                    return true
                }
            }
            if (!waiting.await()) return false
        }
    }

    private fun drainBeforeGrowth(
        queue: Queue,
        capacity: Int,
    ): Deferred<Boolean>? = controller.eventLock.withLock {
        if (state != State.READY || queue.ring.queue.used == 0) {
            queue.minimumCapacity = 0
            return@withLock null
        }
        queue.minimumCapacity = capacity
        queue.space.nextChange()
    }

    private fun transferEntry(queue: Queue, pending: PendingTransfer): RingEntry {
        val descriptor = pending.descriptor
        var shortLength: UInt? = null
        return RingEntry(descriptor.trbs) { position, event ->
            val code = event.completionCode
            if (code in 26u..28u) return@RingEntry false
            if (code == 13u && pending.request.setup != null) {
                shortLength = descriptor.actualLength(position, event.transferLength)
                return@RingEntry false
            }
            val actual = when {
                code == 1u && event.transferLength == 0u -> shortLength ?: descriptor.length
                else -> shortLength ?: descriptor.actualLength(position, event.transferLength)
            }
            val status = when (code) {
                1u -> if (actual < descriptor.length) TransferStatus.SHORT_PACKET
                    else TransferStatus.COMPLETED
                13u -> TransferStatus.SHORT_PACKET
                2u, 3u -> TransferStatus.DATA_ERROR
                4u -> TransferStatus.BABBLE
                5u -> TransferStatus.TRB_ERROR
                6u -> TransferStatus.STALL
                36u -> TransferStatus.SPLIT_ERROR
                else -> TransferStatus.UNKNOWN
            }
            if (!status.successful && state == State.READY) {
                state = State.HALTED
                queues.forEach { it.space.signal(false) }
            }
            queue.pending.remove(pending)
            val result = TransferResult(status, actual)
            controller.completions.addLast(pending to result)
            status.successful
        }
    }

    internal fun complete(event: Trb) {
        val target = pages[event.parameter and (PAGE_SIZE_BYTES - 1uL).inv()] ?: return
        val (queue, segment) = target
        val index = queue.ring.indexOf(event.parameter, segment) ?: return
        val ring = queue.ring.queue
        val used = ring.used
        ring.complete(index, event)
        if (ring.used < used) queue.space.signal(state == State.READY)
    }

    suspend fun cancel(status: TransferStatus, stream: Int? = null): Boolean {
        if (
            stream != null &&
                (streamCount == 0 && stream != 0 || streamCount != 0 && stream !in 1..streamCount)
        ) {
            return false
        }
        val stopped = quiesce(status, stream)
        if (stopped) resume()
        return stopped
    }

    suspend fun quiesce(status: TransferStatus, stream: Int? = null): Boolean {
        controller.eventLock.withLock {
            if (state != State.CLOSED) state = State.PAUSED
            queues.forEach { it.space.signal(false) }
        }
        return mutex.withLock { reset(status, stream) }
    }

    fun resume() {
        controller.eventLock.withLock {
            if (state != State.PAUSED) return@withLock
            state = State.READY
            queues.forEachIndexed { index, queue ->
                if (queue.ring.queue.used != 0) {
                    controller.doorbell.ring(
                        slot.id,
                        dci.toUInt(),
                        if (streamCount == 0) 0 else index + 1,
                    )
                }
            }
        }
    }

    private suspend fun stop(): Boolean {
        val state = hardwareState
        if (
            state == 1u &&
                controller
                    .sendCommand(Trb.newEndpointCommand(TRB_STOP_ENDPOINT, slot.id, dci))
                    .first != 1u
        )
            return false
        if (
            hardwareState == 2u &&
                controller
                    .sendCommand(Trb.newEndpointCommand(TRB_RESET_ENDPOINT, slot.id, dci))
                    .first != 1u
        )
            return false
        return hardwareState == 3u
    }

    private suspend fun reset(status: TransferStatus, selectedStream: Int? = null): Boolean {
        if (state == State.CLOSED) return true
        if (!stop()) return false
        for ((index, queue) in queues.withIndex()) {
            val stream = if (streamCount == 0) 0 else index + 1
            if (selectedStream != null && selectedStream != stream) continue
            val command = Trb.newSetDequeue(slot.id, dci, queue.ring.dequeuePointer, stream)
            if (controller.sendCommand(command).first != 1u) return false
        }
        controller.eventLock.withLock {
            queues.forEachIndexed { index, queue ->
                val stream = if (streamCount == 0) 0 else index + 1
                if (selectedStream != null && selectedStream != stream) return@forEachIndexed
                queue.ring.queue.discard()
                queue.pending.forEach {
                    controller.completions.addLast(it to TransferResult(status, 0u))
                }
                queue.pending.clear()
            }
            if (state == State.HALTED) state = State.READY
        }
        controller.completeTransfers()
        return true
    }

    private suspend fun grow(queue: Queue, capacity: Int, stream: Int): Boolean {
        val ring =
            try {
                ProducerRing(controller.memory, capacity)
            } catch (_: DmaMemory.AllocationFailure) {
                return false
            }
        return withContext(NonCancellable) {
            if (!stop()) {
                ring.free()
                return@withContext false
            }
            if (
                controller
                    .sendCommand(Trb.newSetDequeue(slot.id, dci, ring.dequeuePointer, stream))
                    .first != 1u
            ) {
                ring.free()
                return@withContext false
            }
            val previous = queue.ring
            controller.eventLock.withLock {
                previous.pages.forEach(pages::remove)
                queue.ring = ring
                ring.pages.forEachIndexed { segment, page -> pages[page] = queue to segment }
                queues.forEachIndexed { index, other ->
                    if (other.ring.queue.used != 0)
                        controller.doorbell.ring(
                            slot.id,
                            dci.toUInt(),
                            if (streamCount == 0) 0 else index + 1,
                        )
                }
            }
            previous.free()
            true
        }
    }

    fun close(status: TransferStatus) {
        state = State.CLOSED
        queues.forEach { queue ->
            queue.pending.forEach {
                controller.completions.addLast(it to TransferResult(status, 0u))
            }
            queue.pending.clear()
            queue.ring.queue.discard()
            queue.space.signal(false)
        }
    }

    suspend fun dispose() {
        mutex.withLock { free() }
    }

    fun free() {
        queues.forEach { it.ring.free() }
        streamContexts?.free()
    }
}
