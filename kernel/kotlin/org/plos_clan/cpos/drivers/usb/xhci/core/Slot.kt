package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.coroutines.KernelOneShot
import org.plos_clan.cpos.coroutines.KernelSemaphore
import org.plos_clan.cpos.drivers.usb.bus.UsbDevice
import org.plos_clan.cpos.mem.MmioRegion

const val MAX_SLOTS = 256
const val MAX_ENDPOINTS = 32

class Slot(
    var id: UByte = 0u,
    var active: Boolean = false,
    var portId: Int = 0,
    var speed: UInt = 0u,
    var outContext: MmioRegion? = null,
) {
    var usbDevice: UsbDevice? = null
    val endpoints = arrayOfNulls<Endpoint>(MAX_ENDPOINTS)
}

class Endpoint {
    val ring = TransferRing()
    val semaphore = KernelSemaphore(ring.capacity)
    val promises = Array(ring.capacity) { KernelOneShot<Trb>() }

    fun free() {
        ring.free()
    }
}
