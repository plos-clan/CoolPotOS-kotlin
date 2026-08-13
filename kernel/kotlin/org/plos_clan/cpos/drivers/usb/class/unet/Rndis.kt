package org.plos_clan.cpos.drivers.usb.unet

import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.defs.CLASS_COMM
import org.plos_clan.cpos.drivers.usb.defs.CLASS_DATA
import org.plos_clan.cpos.drivers.usb.defs.CLASS_WIRELESS
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_BULK
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT
import org.plos_clan.cpos.mem.MmioRegion

class RndisDevice(
    val ctrlInterface: UsbInterface,
    val dataInterface: UsbInterface,
    val endpointInterrupt: UByte,
    val endpointBulkIn: UByte,
    val endpointBulkOut: UByte,
) : UsbDriver {
    var ctrlBuffer: MmioRegion? = null
    var rxBuffer: MmioRegion? = null
    var txBuffer: MmioRegion? = null
    val macAddress = UByteArray(6)

    private fun free() {
        ctrlBuffer?.free()
        ctrlBuffer = null

        rxBuffer?.free()
        rxBuffer = null

        txBuffer?.free()
        txBuffer = null
    }

    override fun disconnect() {
        println("RNDIS Device disconnected")
        free()
    }

    override fun handleCompletion(event: CompletionEvent) {}

    companion object {
        fun create(
            ctrlInterface: UsbInterface,
            dataInterface: UsbInterface,
            endpointInterrupt: UByte,
            endpointBulkIn: UByte,
            endpointBulkOut: UByte,
        ): RndisDevice? {
            val ctrlBuffer = MmioRegion.allocate() ?: return null
            val rxBuffer = MmioRegion.allocate(4uL) ?: run {
                ctrlBuffer.free()
                return null
            }
            val txBuffer = MmioRegion.allocate(4uL) ?: run {
                ctrlBuffer.free()
                rxBuffer.free()
                return null
            }

            return RndisDevice(
                ctrlInterface = ctrlInterface,
                dataInterface = dataInterface,
                endpointInterrupt = endpointInterrupt,
                endpointBulkIn = endpointBulkIn,
                endpointBulkOut = endpointBulkOut,
            ).apply {
                this.ctrlBuffer = ctrlBuffer
                this.rxBuffer = rxBuffer
                this.txBuffer = txBuffer
            }
        }
    }
}

suspend fun probeRndis(iface: UsbInterface): UsbDriver? {
    if (!iface.matches(CLASS_WIRELESS, 0x01u.toUByte(), 0x03u.toUByte()) &&
        !iface.matches(CLASS_COMM, 0x02u.toUByte(), 0xffu.toUByte())
    ) {
        return null
    }

    val dataInterface = iface.findSibling(CLASS_DATA, 0xffu.toUByte(), 0xffu.toUByte()) ?: run {
        println("RNDIS: Found control interface, but missing data interface")
        return null
    }

    val endpointInterrupt = iface.findEndpoint(EP_TYPE_INT, true) ?: run {
        println("RNDIS: No Interrupt IN endpoint")
        return null
    }
    val endpointBulkIn = dataInterface.findEndpoint(EP_TYPE_BULK, true) ?: run {
        println("RNDIS: No Bulk IN endpoint")
        return null
    }
    val endpointBulkOut = dataInterface.findEndpoint(EP_TYPE_BULK, false) ?: run {
        println("RNDIS: No Bulk OUT endpoint")
        return null
    }

    val rndis = RndisDevice.create(
        ctrlInterface = iface,
        dataInterface = dataInterface,
        endpointInterrupt = endpointInterrupt.desc.endpointAddress,
        endpointBulkIn = endpointBulkIn.desc.endpointAddress,
        endpointBulkOut = endpointBulkOut.desc.endpointAddress,
    ) ?: return null

    println("Probing RNDIS device on slot ${iface.device.slotId}...")
    return null
}
