package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.DeviceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN

class UsbDevice(
    val host: HostController,
    val slotId: UByte,
    val portId: Int,
    val speed: UInt,
) {
    var desc: DeviceDescriptor? = null
    val interfaces = mutableListOf<UsbInterface>()
    val endpointMap = UsbEndpointMap()

    fun quiesce() {
        boundDrivers().forEach(UsbDriver::quiesce)
    }

    suspend fun free() {
        val drivers = boundDrivers()
        interfaces.forEach { iface ->
            iface.driver = null
        }
        for (driver in drivers) driver.disconnect()
        interfaces.clear()
    }

    private fun boundDrivers(): Set<UsbDriver> = interfaces.mapNotNullTo(mutableSetOf()) {
        it.driver
    }
}

class UsbEndpointMap {
    private val indices = arrayOfNulls<UByte>(32)

    fun get(endpointAddress: UByte): UByte? =
        indices[indexOf(endpointAddress).toInt()]

    fun set(endpointAddress: UByte, ifaceIndex: UByte) {
        indices[indexOf(endpointAddress).toInt()] = ifaceIndex
    }

    companion object {
        private fun indexOf(endpointAddress: UByte): UByte {
            val endpointNumber = endpointAddress and 0x0fu.toUByte()
            val isIn = endpointAddress and REQ_DIR_IN != 0u.toUByte()
            return if (isIn) {
                (endpointNumber.toUInt() + 16u).toUByte()
            } else {
                endpointNumber
            }
        }
    }
}
