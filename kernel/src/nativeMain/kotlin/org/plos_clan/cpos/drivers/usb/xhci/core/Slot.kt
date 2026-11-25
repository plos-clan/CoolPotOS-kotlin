package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.sync.Mutex
import org.plos_clan.cpos.drivers.usb.bus.UsbDevice
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint
import org.plos_clan.cpos.mem.MmioRegion
import kotlin.concurrent.Volatile

const val MAX_SLOTS = 256
const val MAX_ENDPOINTS = 32

class Slot(
    var id: UByte = 0u,
    @Volatile var active: Boolean = false,
    var portId: Int = 0,
    var speed: UInt = 0u,
    var outContext: MmioRegion? = null,
) {
    var usbDevice: UsbDevice? = null
    val mutex = Mutex()
    val endpoints = arrayOfNulls<Endpoint>(MAX_ENDPOINTS)
    val descriptors = arrayOfNulls<UsbEndpoint>(MAX_ENDPOINTS)
}
