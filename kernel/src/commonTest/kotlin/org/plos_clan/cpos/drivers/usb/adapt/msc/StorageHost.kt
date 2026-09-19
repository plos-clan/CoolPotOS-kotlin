package org.plos_clan.cpos.drivers.usb.adapt.msc

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbDevice
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.DmaBuffer
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

internal class StorageHost : HostController {
    class Memory(val bytes: ByteArray, override val physicalAddress: ULong) :
        DmaBuffer, IoBuffer by ByteArrayBuffer(bytes) {
        override val virtualAddress = physicalAddress
        override val size = bytes.size
        var closed = false

        override fun close() {
            assertFalse(closed)
            closed = true
        }
    }

    val memory = mutableListOf<Memory>()
    val requests = mutableListOf<UsbTransfer>()
    val cleared = mutableListOf<UByte>()
    val cancelled = mutableListOf<UByte>()
    var streams = 4
    var onSubmit: (UsbTransfer) -> Unit = {}

    fun allocate(size: Int): DmaBuffer =
        Memory(ByteArray(size), (memory.size + 1).toULong() shl 32).also(memory::add)

    fun interfaceFor(uas: Boolean, speed: UInt = 4u): UsbInterface {
        val device = UsbDevice(this, 1u, 1, speed)
        val iface =
            UsbInterface(
                device,
                InterfaceDescriptor(
                    0u,
                    0u,
                    if (uas) 4u else 2u,
                    8u,
                    6u,
                    if (uas) 0x62u else 0x50u,
                    0u,
                ),
            )
        val addresses = if (uas) listOf<UByte>(1u, 0x82u, 0x83u, 4u) else listOf<UByte>(0x81u, 2u)
        addresses.forEachIndexed { index, address ->
            val endpoint = UsbEndpoint(EndpointDescriptor(address, 2u, 512u, 0u))
            if (uas) {
                endpoint.extraDescriptors.add(byteArrayOf(4, 0x24, (index + 1).toByte(), 0))
                if (speed >= 4u) endpoint.ssDesc = SsEndpointCompanionDescriptor(0u, 4u, 0u)
            }
            iface.activeSetting.endpoints.add(endpoint)
        }
        device.interfaces.add(iface)
        device.updateEndpoints()
        return iface
    }

    fun bytes(request: UsbTransfer): ByteArray {
        val buffer = request.buffers.single()
        val allocation = memory.single {
            buffer.physicalAddress >= it.physicalAddress &&
                buffer.physicalAddress - it.physicalAddress < it.size.toULong()
        }
        assertFalse(allocation.closed)
        val offset = (buffer.physicalAddress - allocation.physicalAddress).toInt()
        return allocation.bytes.copyOfRange(offset, offset + buffer.length.toInt())
    }

    fun complete(
        request: UsbTransfer,
        bytes: ByteArray? = null,
        status: TransferStatus = TransferStatus.COMPLETED,
    ) {
        if (bytes != null) {
            val buffer = request.buffers.single()
            val allocation = memory.single {
                buffer.physicalAddress >= it.physicalAddress &&
                    buffer.physicalAddress - it.physicalAddress < it.size.toULong()
            }
            assertFalse(allocation.closed)
            assertTrue(bytes.size <= buffer.length.toInt())
            bytes.copyInto(
                allocation.bytes,
                (buffer.physicalAddress - allocation.physicalAddress).toInt(),
            )
        }
        request.complete(
            TransferResult(
                status,
                bytes?.size?.toUInt() ?: if (status.successful) request.length else 0u,
            )
        )
    }

    fun status(tag: Int, code: Int = 0, stream: Int = tag, sense: ByteArray = byteArrayOf()) {
        val request = requests.first {
            it.endpointAddress == 0x82u.toUByte() &&
                it.streamId.toInt() == stream &&
                !it.isCompleted
        }
        val bytes = ByteArray(16 + sense.size)
        bytes[0] = 3
        bytes[6] = code.toByte()
        BigEndianBuffer(bytes).write(2, 2, tag.toULong())
        BigEndianBuffer(bytes).write(14, 2, sense.size.toULong())
        sense.copyInto(bytes, 16)
        complete(request, bytes)
    }

    override suspend fun submit(slotId: UByte, transfer: UsbTransfer): Boolean {
        assertTrue(transfer.claim())
        requests.add(transfer)
        onSubmit(transfer)
        return true
    }

    override suspend fun cancel(
        slotId: UByte,
        endpointAddress: UByte,
        status: TransferStatus,
        streamId: UShort?,
    ): Boolean {
        cancelled.add(endpointAddress)
        requests
            .filter {
                !it.isCompleted &&
                    it.endpointAddress == endpointAddress &&
                    (streamId == null || it.streamId == streamId)
            }
            .forEach { complete(it, status = status) }
        return true
    }

    override suspend fun clearHalt(slotId: UByte, endpointAddress: UByte): Boolean {
        cleared.add(endpointAddress)
        return true
    }

    override suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Unit = Unit

    override suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Unit =
        Unit

    override suspend fun allocateStreams(
        slotId: UByte,
        endpoints: List<UsbEndpoint>,
        count: Int,
    ): Int = minOf(streams, count)

    override suspend fun disableDevice(slotId: UByte) = Unit
}
