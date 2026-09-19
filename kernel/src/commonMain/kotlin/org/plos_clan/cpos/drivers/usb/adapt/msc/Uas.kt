package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.scsi.ScsiDirection
import org.plos_clan.cpos.drivers.scsi.ScsiResult
import org.plos_clan.cpos.drivers.scsi.ScsiSense
import org.plos_clan.cpos.drivers.scsi.ScsiStatus
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.mem.DmaBuffer
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

class UasTransport
private constructor(
    iface: UsbInterface,
    allocate: (Int) -> DmaBuffer?,
    override val endpoints: List<UByte>,
    private val streamCount: Int,
) : StorageTransport(iface, allocate) {
    override val slots = List(maxOf(1, streamCount)) { Slot(it + 1) }
    private val available =
        Channel<Slot>(slots.size).also { channel ->
            slots.forEach { check(channel.trySend(it).isSuccess) }
        }

    override suspend fun execute(
        lun: ULong,
        command: ByteArray,
        direction: ScsiDirection,
        buffer: IoBuffer?,
        offset: Int,
        length: Int,
    ): ScsiResult {
        if (command.size > 268 || !valid(command, direction, buffer, offset, length)) {
            return ScsiResult(ScsiStatus.IO_ERROR)
        }
        if (!connected) return unavailable
        val slot = available.receive()
        try {
            return slot.lock.withLock {
                withContext(NonCancellable) {
                    if (!connected) return@withContext unavailable
                    exchange(slot, lun, command, direction, buffer, offset, length)
                }
            }
        } finally {
            check(available.trySend(slot).isSuccess)
        }
    }

    private suspend fun exchange(
        slot: Slot,
        lun: ULong,
        command: ByteArray,
        direction: ScsiDirection,
        buffer: IoBuffer?,
        offset: Int,
        length: Int,
    ): ScsiResult = coroutineScope {
        val memory = slot.metadata() ?: return@coroutineScope ScsiResult(ScsiStatus.NO_MEMORY)
        val extra = (maxOf(0, command.size - 16) + 3) / 4
        val iu = ByteArray(32 + extra * 4)
        iu[0] = 1
        iu[6] = (extra * 4).toByte()
        BigEndianBuffer(iu).apply {
            write(2, 2, slot.tag.toULong())
            write(8, 8, lun)
        }
        command.copyInto(iu, 16)
        memory.copyFrom(0, iu, 0, iu.size)
        val stream = if (streamCount == 0) 0 else slot.tag
        var status =
            async(start = CoroutineStart.UNDISPATCHED) {
                slot.transfer(endpoints[1], memory, 512, STATUS_SIZE, stream)
            }
        var data: Deferred<TransferResult>? = null
        var finished = false
        val endpoint = endpoints[if (direction == ScsiDirection.IN) 2 else 3]
        if (streamCount != 0 && length != 0) {
            data =
                async(start = CoroutineStart.UNDISPATCHED) {
                    slot
                        .data(endpoint, direction, checkNotNull(buffer), offset, length, stream)
                        .also {
                            if (!it.successful && !finished) stop()
                        }
                }
        }
        val sent = slot.transfer(endpoints[0], memory, 0, iu.size)
        if (!sent.successful || sent.actualLength != iu.size.toUInt()) {
            stop()
            return@coroutineScope unavailable
        }
        while (true) {
            val received = status.await()
            if (!received.successful || received.actualLength < 4u) {
                stop()
                return@coroutineScope unavailable
            }
            val response = ByteArray(received.actualLength.toInt())
            memory.copyTo(512, response, 0, response.size)
            val fields = BigEndianBuffer(response)
            val id = response[0].toUByte().toInt()
            if (fields.read(2, 2) != slot.tag.toULong()) {
                stop()
                return@coroutineScope unavailable
            }
            if (id == 6 || id == 7) {
                val expected = if (direction == ScsiDirection.IN) 6 else 7
                if (
                    streamCount != 0 ||
                        data != null ||
                        length == 0 ||
                        id != expected ||
                        response.size != 4
                ) {
                    stop()
                    return@coroutineScope unavailable
                }
                status =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        slot.transfer(endpoints[1], memory, 512, STATUS_SIZE)
                    }
                data =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        slot.data(endpoint, direction, checkNotNull(buffer), offset, length).also {
                            if (!it.successful && !finished) stop()
                        }
                    }
                continue
            }
            if (id != 3 || response.size < 16) {
                stop()
                return@coroutineScope unavailable
            }
            val senseLength = fields.read(14, 2).toInt()
            if (senseLength > response.size - 16) {
                stop()
                return@coroutineScope unavailable
            }
            val code = response[6].toUByte().toInt()
            finished = true
            if (code != 0 && data?.isCompleted == false) {
                if (
                    !iface.device.host.cancel(
                        iface.device.slotId,
                        endpoint,
                        TransferStatus.CANCELLED,
                        stream.toUShort(),
                    )
                ) {
                    stop()
                }
            }
            val transferred = data?.await()
            if (
                code == 0 &&
                    (length != 0 && transferred == null || transferred?.successful == false)
            ) {
                stop()
                return@coroutineScope unavailable
            }
            val result =
                when (code) {
                    0 -> ScsiStatus.GOOD
                    2 -> ScsiStatus.CHECK_CONDITION
                    8,
                    0x28 -> ScsiStatus.BUSY
                    else -> ScsiStatus.IO_ERROR
                }
            return@coroutineScope ScsiResult(
                result,
                transferred?.actualLength?.toInt() ?: 0,
                ScsiSense.parse(response.copyOfRange(16, 16 + senseLength)),
            )
        }
        @Suppress("UNREACHABLE_CODE") unavailable
    }

    companion object {
        private const val STATUS_SIZE = 16 + 252

        suspend fun create(
            iface: UsbInterface,
            allocate: (Int) -> DmaBuffer?,
            queueDepth: Int = 32,
        ): UasTransport? {
            require(queueDepth in 1..65535)
            val pipes = arrayOfNulls<UsbEndpoint>(4)
            for (endpoint in iface.endpoints) {
                if (endpoint.desc.transferType != 2) return null
                val usages =
                    endpoint.extraDescriptors.filter { it.size >= 2 && it[1] == 0x24.toByte() }
                if (usages.size != 1) return null
                val descriptor = usages.single()
                if (descriptor.size != 4) return null
                val id = descriptor[2].toUByte().toInt()
                if (id !in 1..4 || pipes[id - 1] != null) return null
                val input = endpoint.desc.endpointAddress.toInt() and 0x80 != 0
                if (input != (id == 2 || id == 3)) return null
                pipes[id - 1] = endpoint
            }
            if (pipes.any { it == null }) return null
            val endpoints = pipes.filterNotNull()
            val superSpeed = iface.device.speed >= 4u
            val count = if (superSpeed) iface.allocateStreams(endpoints.drop(1), queueDepth) else 0
            if (superSpeed && count == 0) return null
            return UasTransport(iface, allocate, endpoints.map { it.desc.endpointAddress }, count)
        }
    }
}
