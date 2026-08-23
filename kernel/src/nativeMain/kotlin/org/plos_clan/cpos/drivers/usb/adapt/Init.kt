package org.plos_clan.cpos.drivers.usb.adapt

import org.plos_clan.cpos.drivers.usb.adapt.hid.probeKbd
import org.plos_clan.cpos.drivers.usb.adapt.hid.probeMouse
import org.plos_clan.cpos.drivers.usb.adapt.unet.probeRndis
import org.plos_clan.cpos.drivers.usb.bus.usbDrivers

object ClassDrivers {
    fun initialize() {
        usbDrivers.add(::probeKbd)
        usbDrivers.add(::probeMouse)
        usbDrivers.add(::probeRndis)
    }
}
