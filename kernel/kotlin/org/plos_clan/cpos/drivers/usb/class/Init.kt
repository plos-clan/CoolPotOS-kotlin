package org.plos_clan.cpos.drivers.usb

import org.plos_clan.cpos.drivers.usb.bus.usbDrivers
import org.plos_clan.cpos.drivers.usb.hid.probeKbd
import org.plos_clan.cpos.drivers.usb.hid.probeMouse
import org.plos_clan.cpos.drivers.usb.unet.probeRndis

object ClassDrivers {
    fun initialize() {
        usbDrivers.add(::probeKbd)
        usbDrivers.add(::probeMouse)
        usbDrivers.add(::probeRndis)
    }
}
