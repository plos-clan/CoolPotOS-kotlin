package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.drivers.usb.bus.ControlTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint

class XhciHostController(private val xhci: Xhci) : HostController {
    override suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Boolean =
        xhci.updateEp0Mps(slotId, mps)

    override suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Boolean =
        xhci.configureEndpoints(slotId, endpoints)

    override suspend fun submitControl(args: ControlTransferArgs): Boolean =
        xhci.submitControl(args)

    override suspend fun submitTransfer(args: GeneralTransferArgs): Boolean =
        xhci.submitTransfer(args)
}
