package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.drivers.usb.bus.ControlTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.HostController
import org.plos_clan.cpos.drivers.usb.bus.UsbEndpoint

class XhciHostController(private val xhci: Xhci) : HostController {
    override suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Unit? =
        xhci.updateEp0Mps(slotId, mps)

    override suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Unit? =
        xhci.configureEndpoints(slotId, endpoints)

    override suspend fun submitControl(args: ControlTransferArgs): Unit? =
        xhci.submitControl(args)

    override suspend fun submitTransfer(args: GeneralTransferArgs): Unit? =
        xhci.submitTransfer(args)
}
