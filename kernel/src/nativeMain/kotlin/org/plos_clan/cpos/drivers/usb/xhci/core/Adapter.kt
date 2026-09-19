package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.drivers.usb.bus.transfer
import org.plos_clan.cpos.drivers.usb.defs.REQ_CLEAR_FEATURE
import org.plos_clan.cpos.drivers.usb.defs.REQ_REC_ENDPOINT
import org.plos_clan.cpos.drivers.usb.defs.SPEED_SUPER
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

class XhciHostController(private val xhci: Xhci) : HostController {
    override suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Unit? =
        xhci.updateEp0Mps(slotId, mps)

    override suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Unit? =
        try {
            xhci.configureEndpoints(slotId, endpoints)
        } catch (_: DmaMemory.AllocationFailure) {
            null
        }

    override suspend fun submit(slotId: UByte, transfer: UsbTransfer): Boolean {
        if (!transfer.claim()) return false
        if (
            !xhci.capability.supports64BitAddressing &&
                transfer.buffers.any {
                    it.physicalAddress > UInt.MAX_VALUE.toULong() ||
                        it.length.toULong() > 0x1_0000_0000uL - it.physicalAddress
                }
        )
            return false
        return endpoint(slotId, transfer.endpointAddress)?.submit(transfer) ?: false
    }

    override suspend fun cancel(
        slotId: UByte,
        endpointAddress: UByte,
        status: TransferStatus,
    ): Boolean =
        withContext(NonCancellable) {
            val endpoint = endpoint(slotId, endpointAddress) ?: return@withContext true
            if (!endpoint.cancel(status)) xhci.fail()
            true
        }

    override suspend fun clearHalt(slotId: UByte, endpointAddress: UByte): Boolean =
        withContext(NonCancellable) {
            val endpoint = endpoint(slotId, endpointAddress) ?: return@withContext false
            if (!endpoint.quiesce(TransferStatus.CANCELLED)) {
                xhci.fail()
                return@withContext false
            }
            if (endpointAddress != 0u.toUByte()) {
                val device = xhci.slots[slotId.toInt()].usbDevice ?: return@withContext false
                val setup =
                    SetupPacket(
                        REQ_REC_ENDPOINT,
                        REQ_CLEAR_FEATURE,
                        index = endpointAddress.toUShort(),
                    )
                if (!device.transfer(UsbTransfer(0u, setup = setup), 5_000).successful) {
                    return@withContext false
                }
            }
            endpoint.resume()
            true
        }

    override suspend fun allocateStreams(
        slotId: UByte,
        endpoints: List<UsbEndpoint>,
        count: Int,
    ): Int {
        require(count >= 0)
        val slot = xhci.slots[slotId.toInt()]
        if (
            slot.speed < SPEED_SUPER.toUInt() ||
                endpoints.isEmpty() ||
                endpoints.any { it.desc.transferType != 2 || it !in slot.descriptors }
        )
            return 0
        val allocation =
            StreamAllocation(
                count,
                endpoints.minOf { it.ssDesc?.maxStreams ?: 0 },
                xhci.capability.maxStreamContexts,
            )
        var allocated = allocation.count
        if (count != 0 && allocated == 0) return 0
        do {
            val streams = endpoints.associate { it.desc.endpointAddress to allocated }
            try {
                return if (
                    xhci.configureEndpoints(slotId, slot.descriptors.filterNotNull(), streams) !=
                        null
                ) {
                    allocated
                } else 0
            } catch (_: DmaMemory.AllocationFailure) {
                allocated /= 2
            }
        } while (allocated != 0)
        return 0
    }

    private fun endpoint(slotId: UByte, address: UByte): Endpoint? {
        if (address.toInt() and 0x70 != 0 || address == 0x80u.toUByte()) return null
        val dci =
            if (address == 0u.toUByte()) 1
            else (address.toInt() and 0x0f) * 2 + if (address.toInt() and 0x80 != 0) 1 else 0
        return xhci.slots[slotId.toInt()].endpoints[dci]
    }

    override suspend fun disableDevice(slotId: UByte) =
        withContext(NonCancellable) {
            val slot = xhci.slots[slotId.toInt()]
            slot.usbDevice?.quiesce()
            xhci.disableSlot(slotId)
            xhci.eventLock.withLock {
                slot.active = false
                slot.endpoints.forEach { it?.close(TransferStatus.DISCONNECTED) }
            }
            xhci.completeTransfers()
        }
}
