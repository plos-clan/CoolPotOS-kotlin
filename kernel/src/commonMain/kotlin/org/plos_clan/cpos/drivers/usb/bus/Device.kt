package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.coroutines.sync.Mutex
import org.plos_clan.cpos.drivers.usb.defs.DeviceDescriptor

class UsbDevice(
    val host: HostController,
    val slotId: UByte,
    val portId: Int,
    val speed: UInt,
) {
    var desc: DeviceDescriptor? = null
    val interfaces = mutableListOf<UsbInterface>()
    internal val configurationLock = Mutex()
    private val endpointOwners = arrayOfNulls<UsbInterface>(32)

    internal fun endpointOwner(address: UByte): UsbInterface? {
        val index = (address.toInt() and 15) or ((address.toInt() shr 3) and 16)
        return endpointOwners[index]
    }

    internal fun updateEndpoints() {
        endpointOwners.fill(null)
        for (iface in interfaces) for (endpoint in iface.endpoints) {
            val address = endpoint.desc.endpointAddress.toInt()
            endpointOwners[(address and 15) or ((address shr 3) and 16)] = iface
        }
    }

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
        endpointOwners.fill(null)
    }

    private fun boundDrivers(): Set<UsbDriver> =
        interfaces.mapNotNullTo(mutableSetOf()) {
            it.driver
        }
}
