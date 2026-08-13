package org.plos_clan.cpos.drivers.usb.bus

internal fun UsbDevice.matchDrivers() {
    if (usbDrivers.isEmpty()) {
        println("No USB drivers registered")
        return
    }

    for (iface in interfaces) {
        if (iface.driver != null) {
            continue
        }

        for (probeFn in usbDrivers) {
            val driver = probeFn(iface)
            if (driver != null) {
                iface.driver = driver
                println("Interface bound to driver successfully")
                break
            }
        }
    }
}
