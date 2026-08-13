package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.coroutines.yield
import org.plos_clan.cpos.drivers.usb.bus.UsbDevice
import org.plos_clan.cpos.drivers.usb.bus.enumerate
import org.plos_clan.cpos.drivers.usb.xhci.regs.PORT_CSC
import org.plos_clan.cpos.drivers.usb.xhci.regs.PORT_PRC
import org.plos_clan.cpos.drivers.usb.xhci.regs.Port

suspend fun Xhci.xhciHubThread() {
    if (!testCommandRing()) {
        return
    }
    checkPorts()

    while (true) {
        portSemaphore.acquire()
        checkPorts()
    }
}

internal suspend fun Xhci.waitPortReset(port: Port): Boolean {
    repeat(1_000_000) {
        if (port.isInReset) {
            yield()
            return@repeat
        }
        if (port.hasResetChange) {
            port.updatePortSc(PORT_PRC)
        }
        return port.isEnabled
    }

    println("Port ${port.id} reset timeout")
    return false
}

suspend fun Xhci.checkPorts() {
    for (index in 0 until capability.maxPorts.toInt()) {
        val port = Port(operational.baseAddress, index)
        val coldPlugged = port.isConnected && !port.isEnabled

        if (port.hasConnectionChange || coldPlugged) {
            handlePort(port)
        }
    }
}

internal suspend fun Xhci.handlePort(port: Port) {
    if (port.hasConnectionChange) {
        port.updatePortSc(PORT_CSC)
    }

    if (port.isConnected) {
        attachDevice(port)
    } else {
        println("Port ${port.id} disconnected")
        val slotId = portToSlot[port.id]

        if (slotId != 0u.toUByte()) {
            cleanupSlotOnFailure(slotId)
            portToSlot[port.id] = 0u
        }
    }
}

internal suspend fun Xhci.attachDevice(port: Port) {
    println("Port ${port.id} connected, resetting...")

    if (!port.reset() || !waitPortReset(port)) {
        println("Port ${port.id} reset failed")
        return
    }

    val slotId = enableSlot()
        ?: run {
            println("Failed to enable slot for port ${port.id}")
            return
        }

    println("Device assigned to slot $slotId")

    if (!setupSlotDevice(port, slotId)) {
        println("Device init failed for slot $slotId")
        cleanupSlotOnFailure(slotId)
    }
}

internal suspend fun Xhci.setupSlotDevice(port: Port, slotId: UByte): Boolean {
    val speedId = port.speedId
    println("Port ${port.id} enabled (speed: $speedId)")

    if (!addressDevice(port.id, slotId, speedId)) {
        return false
    }

    val device = UsbDevice(
        host = hostController,
        slotId = slotId,
        portId = port.id,
        speed = speedId,
    )
    slots[slotId.toInt()].usbDevice = device

    if (!device.enumerate()) {
        println("Enumeration failed for slot $slotId")
        device.free()
        slots[slotId.toInt()].usbDevice = null
        return false
    }

    portToSlot[port.id] = slotId
    return true
}
