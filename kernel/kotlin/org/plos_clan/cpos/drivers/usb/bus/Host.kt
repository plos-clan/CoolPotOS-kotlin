package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor

data class ControlTransferArgs(
    val slotId: UByte,
    val setup: SetupPacket,
    val bufferPhysicalAddress: ULong,
)

data class GeneralTransferArgs(
    val slotId: UByte,
    val endpointAddress: UByte,
    val bufferPhysicalAddress: ULong,
    val length: UInt,
)

interface HostController {
    suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Boolean
    suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Boolean
    suspend fun submitControl(args: ControlTransferArgs): Boolean
    suspend fun submitTransfer(args: GeneralTransferArgs): Boolean
}
