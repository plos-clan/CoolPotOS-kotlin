@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.set
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

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
    }

    private val mutex = Mutex()
    private val space = Channel<Unit>(Channel.CONFLATED)
    private val queues = mutableListOf<Queue>()
    private val pages = mutableMapOf<ULong, Pair<Queue, Int>>()
    private var streamContexts: MmioRegion? = null
    private var state = State.READY

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

    suspend fun submit(request: UsbTransfer): Boolean = mutex.withLock {
        if (state == State.CLOSED || state == State.PAUSED || !slot.active || request.isCompleted) {
            return@withLock false
        }
        val stream = request.streamId.toInt()
        if (streamCount == 0 && stream != 0 || streamCount != 0 && stream !in 1..streamCount) {
            return@withLock false
        }
        val index = if (streamCount == 0) 0 else stream - 1
        if (
            state == State.HALTED &&
                (dci != 1 || !withContext(NonCancellable) { reset(TransferStatus.CANCELLED) })
        ) {
            return@withLock false
        }
        if (request.setup != null && dci != 1 || request.setup == null && dci == 1)
            return@withLock false
        val pending =
            try {
                PendingTransfer(request, controller.memory, packetSize)
            } catch (_: DmaMemory.AllocationFailure) {
                return@withLock false
            }
        var submitted = false
        try {
            val descriptor = pending.descriptor
            val queue = queues[index]
            if (
                descriptor.trbs.size > queue.ring.capacity &&
                    !grow(queue, descriptor.trbs.size, stream)
            ) {
                return@withLock false
            }
            var shortLength: UInt? = null
            val entry =
                RingEntry(descriptor.trbs) { position, event ->
                    val code = event.completionCode
                    if (code in 26u..28u) return@RingEntry false
                    val status =
                        when (code) {
                            1u -> TransferStatus.COMPLETED
                            13u -> TransferStatus.SHORT_PACKET
                            2u,
                            3u -> TransferStatus.DATA_ERROR
                            4u -> TransferStatus.BABBLE
                            5u -> TransferStatus.TRB_ERROR
                            6u -> TransferStatus.STALL
                            36u -> TransferStatus.SPLIT_ERROR
                            else -> TransferStatus.UNKNOWN
                        }
                    if (code == 13u && request.setup != null) {
                        shortLength = descriptor.actualLength(position, event.transferLength)
                        return@RingEntry false
                    }
                    val actual =
                        shortLength
                            ?: if (code == 1u && event.transferLength == 0u) descriptor.length
                            else descriptor.actualLength(position, event.transferLength)
                    val finalStatus =
                        if (code == 1u && actual < descriptor.length) TransferStatus.SHORT_PACKET
                        else status
                    if (!status.successful && state == State.READY) state = State.HALTED
                    queue.pending.remove(pending)
                    controller.completions.addLast(pending to TransferResult(finalStatus, actual))
                    status.successful
                }
            while (true) {
                val queued =
                    controller.eventLock.withLock {
                        if (state != State.READY || !slot.active) return@withLock null
                        if (!queue.ring.enqueue(entry)) return@withLock false
                        queue.pending.add(pending)
                        submitted = true
                        controller.doorbell.ring(slot.id, dci.toUInt(), stream)
                        true
                    } ?: return@withLock false
                if (queued) return@withLock true
                space.receive()
            }
            @Suppress("UNREACHABLE_CODE") false
        } finally {
            if (!submitted) pending.free()
        }
    }

    internal fun complete(event: Trb) {
        val target = pages[event.parameter and (PAGE_SIZE_BYTES - 1uL).inv()] ?: return
        val (queue, segment) = target
        val index = queue.ring.indexOf(event.parameter, segment) ?: return
        queue.ring.queue.complete(index, event)
        space.trySend(Unit)
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
            space.trySend(Unit)
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
            space.trySend(Unit)
        }
        controller.completeTransfers()
        return true
    }

    private suspend fun grow(queue: Queue, capacity: Int, stream: Int): Boolean {
        while (controller.eventLock.withLock { queue.ring.queue.used != 0 }) {
            if (state != State.READY) return false
            space.receive()
        }
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
        }
        space.trySend(Unit)
    }

    suspend fun dispose() {
        mutex.withLock { free() }
    }

    fun free() {
        queues.forEach { it.ring.free() }
        streamContexts?.free()
    }
}
