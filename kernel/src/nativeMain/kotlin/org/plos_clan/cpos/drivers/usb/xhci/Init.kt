@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci

import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.pcie.PciDevice
import org.plos_clan.cpos.drivers.usb.xhci.core.resetController
import org.plos_clan.cpos.drivers.usb.xhci.core.setupCommandRing
import org.plos_clan.cpos.drivers.usb.xhci.core.setupDcbaa
import org.plos_clan.cpos.drivers.usb.xhci.core.setupInterrupter
import org.plos_clan.cpos.drivers.usb.xhci.core.takeOwnership
import org.plos_clan.cpos.drivers.usb.xhci.core.xhciHubThread
import org.plos_clan.cpos.mem.MmioAddress
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.drivers.usb.xhci.core.Xhci as XhciController

object Xhci {
    private val controllers = mutableListOf<XhciController>()

    fun initialize(device: PciDevice) {
        val bar = device.bars[0] ?: return
        val region = MmioRegion.map(bar.address, bar.size) ?: return
        val baseAddress = region.addressAt(0uL, 4) ?: return
        initController(baseAddress, device)
    }

    private fun initController(baseAddress: MmioAddress, device: PciDevice) {
        val xhci = XhciController(baseAddress)
        printInfo(xhci)

        if (!xhci.takeOwnership()) {
            println("xHCI BIOS handoff failed")
            return
        }

        if (!xhci.resetController()) {
            println("xHCI initialization failed")
            return
        }

        val maxSlots = xhci.capability.maxSlots
        xhci.operational.setMaxSlotsEnabled(maxSlots)

        xhci.setupDcbaa(maxSlots)
        xhci.setupCommandRing()
        xhci.setupInterrupter()

        val interrupt = device.interrupt ?: run {
            println("xHCI controller has no interrupt")
            return
        }

        interrupt.register(xhci::handleIrq, device.bars, 0u) ?: run {
            println("Failed to register xHCI interrupt")
            return
        }

        xhci.operational.start()
        println("xHCI Initialized successfully")

        controllers.add(xhci)
        KernelCoroutines.launch("xhci-hub") {
            xhci.xhciHubThread()
        }
    }

    private fun printInfo(xhci: XhciController) {
        val version = xhci.capability.version
        val maxSlots = xhci.capability.maxSlots
        val maxPorts = xhci.capability.maxPorts

        val major = (version.toUInt() shr 8).toString(16)
        val minor = (version.toUInt() and 0xffu).toString(16)
        println("xHCI Version: $major.$minor")
        println("Max Slots: $maxSlots, Max Ports: $maxPorts")

        if (xhci.capability.supports64BitAddressing) {
            println("Controller supports 64-bit address")
        }
    }
}
