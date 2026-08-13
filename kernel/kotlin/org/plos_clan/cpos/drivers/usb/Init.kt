package org.plos_clan.cpos.drivers.usb

import org.plos_clan.cpos.drivers.pcie.Pcie
import org.plos_clan.cpos.drivers.pcie.PciDeviceType
import org.plos_clan.cpos.drivers.usb.xhci.Xhci

object Usb {
    fun initialize() {
        println("Initializing USB subsystem...")

        for (device in Pcie.enumeratedDevices) {
            if (device.deviceType != PciDeviceType.USB_CONTROLLER) {
                continue
            }

            when (device.progIf) {
                0x30u.toUByte() -> {
                    println("Found xHCI controller")
                    Xhci.initialize(device)
                }
                else -> {
                    println("Unknown USB interface: ${device.progIf.toString(16)}")
                }
            }
        }
    }
}
