@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.scsi.ScsiDirection
import org.plos_clan.cpos.drivers.scsi.ScsiResult
import org.plos_clan.cpos.drivers.scsi.ScsiStatus
import org.plos_clan.cpos.drivers.scsi.ScsiTransport
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.drivers.usb.bus.transfer
import org.plos_clan.cpos.mem.DmaBuffer
import org.plos_clan.cpos.mem.IoBuffer

abstract class StorageTransport(
    protected val iface: UsbInterface,
    protected val allocate: (Int) -> DmaBuffer?,
) : ScsiTransport() {
    private val online = AtomicBoolean(true)
    protected abstract val endpoints: List<UByte>
    protected abstract val slots: List<Slot>
    override val maximumTransferBytes = 1024 * 1024

    override val connected: Boolean
        get() = online.load()

    fun quiesce() {
        online.store(false)
    }

    suspend fun close() =
        withContext(NonCancellable) {
            stop()
            for (slot in slots) slot.lock.withLock { slot.close() }
        }

    protected suspend fun stop() {
        quiesce()
        for (endpoint in endpoints) {
            iface.device.host.cancel(iface.device.slotId, endpoint, TransferStatus.CANCELLED)
        }
    }

    protected fun valid(
        command: ByteArray,
        direction: ScsiDirection,
        buffer: IoBuffer?,
        offset: Int,
        length: Int,
    ): Boolean {
        if (
            command.isEmpty() ||
                offset < 0 ||
                length !in 0..maximumTransferBytes ||
                offset > Int.MAX_VALUE - length ||
                (direction == ScsiDirection.NONE) != (length == 0)
        )
            return false
        if (length == 0) return true
        return if (direction == ScsiDirection.IN) buffer?.prepareWrite(offset, length) != null
        else buffer?.prepareRead(offset, length) != null
    }

    protected inner class Slot(val tag: Int) {
        val lock = Mutex()
        private var metadata: DmaBuffer? = null
        private var staging: DmaBuffer? = null

        fun metadata(): DmaBuffer? = metadata ?: allocate(4096)?.also { metadata = it }

        suspend fun data(
            endpoint: UByte,
            direction: ScsiDirection,
            buffer: IoBuffer,
            offset: Int,
            length: Int,
            stream: Int = 0,
        ): TransferResult {
            if (buffer is DmaBuffer) return transfer(endpoint, buffer, offset, length, stream)
            var memory = staging
            if (memory == null || memory.size < length) {
                val replacement =
                    allocate(length) ?: return TransferResult(TransferStatus.DRIVER_ERROR, 0u)
                memory?.close()
                memory = replacement
                staging = replacement
            }
            if (
                direction == ScsiDirection.OUT &&
                    memory.copyFrom(0, buffer, offset, length) != length
            ) {
                return TransferResult(TransferStatus.DRIVER_ERROR, 0u)
            }
            val result = transfer(endpoint, memory, 0, length, stream)
            if (direction == ScsiDirection.IN && result.actualLength != 0u) {
                val count = result.actualLength.toInt()
                if (buffer.copyFrom(offset, memory, 0, count) != count) {
                    return TransferResult(TransferStatus.DRIVER_ERROR, 0u)
                }
            }
            return result
        }

        suspend fun transfer(
            endpoint: UByte,
            memory: DmaBuffer,
            offset: Int,
            length: Int,
            stream: Int = 0,
        ): TransferResult {
            if (!connected) return TransferResult(TransferStatus.DISCONNECTED, 0u)
            val request =
                UsbTransfer(
                    endpoint,
                    listOf(
                        UsbBuffer(
                            memory.physicalAddress + offset.toUInt(),
                            length.toUInt(),
                            memory.virtualAddress + offset.toUInt(),
                        )
                    ),
                    stream.toUShort(),
                )
            return iface.device.transfer(request, 30_000)
        }

        fun close() {
            metadata?.close()
            staging?.close()
            metadata = null
            staging = null
        }
    }

    protected val unavailable: ScsiResult
        get() = ScsiResult(if (connected) ScsiStatus.IO_ERROR else ScsiStatus.NO_DEVICE)
}
