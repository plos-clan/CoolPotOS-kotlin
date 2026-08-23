@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import bridge.asm_pause
import org.plos_clan.cpos.drivers.usb.xhci.regs.LegacySupport

private fun Xhci.waitReady(): Boolean {
    repeat(1_000_000) {
        if (!operational.notReady) {
            return true
        }
        asm_pause()
    }
    return false
}

private fun Xhci.waitHalted(): Boolean {
    repeat(1_000_000) {
        if (operational.isHalted) {
            return true
        }
        asm_pause()
    }
    return false
}

private fun Xhci.waitResetComplete(): Boolean {
    repeat(1_000_000) {
        if ((operational.readUsbCommand() and 2u) == 0u) {
            return true
        }
        asm_pause()
    }
    return false
}

private fun waitBiosRelease(legacy: LegacySupport): Boolean {
    repeat(1_000_000) {
        if (!legacy.isBiosOwned) {
            return true
        }
        asm_pause()
    }
    return false
}

fun Xhci.takeOwnership(): Boolean {
    val legacy = capability.legacySupport() ?: return true

    if (legacy.isBiosOwned) {
        println("Requesting xHCI ownership from BIOS")
        legacy.requestOsOwnership()

        if (!waitBiosRelease(legacy)) {
            println("xHCI BIOS handoff timed out")
            return false
        }

        println("xHCI BIOS ownership released")
    }

    legacy.sanitizeSmi()
    return true
}

fun Xhci.resetController(): Boolean {
    if (operational.isRunning) {
        println("Controller is running, stopping")
        operational.stop()

        if (!waitHalted()) {
            println("Failed to stop controller")
            return false
        }
    }

    operational.reset()

    if (!waitResetComplete()) {
        println("Reset timeout")
        return false
    }

    if (!waitReady()) {
        println("Controller stuck in not ready state")
        return false
    }

    println("xHCI controller reset complete")
    return true
}
