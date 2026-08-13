package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

data class ControlTransferArgs(
    val slotId: UByte = 0u,
    val setup: SetupPacket,
    val bufferPhysicalAddress: ULong = 0uL,
)

data class GeneralTransferArgs(
    val slotId: UByte = 0u,
    val endpointAddress: UByte = 0u,
    val bufferPhysicalAddress: ULong = 0uL,
    val length: UInt = 0u,
)

interface HostController {
    suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Boolean
    suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Boolean
    suspend fun submitControl(args: ControlTransferArgs): Boolean
    suspend fun submitTransfer(args: GeneralTransferArgs): Boolean
}

suspend fun UsbDevice.submitControl(args: ControlTransferArgs): Boolean {
    val finalArgs = args.copy(slotId = slotId)
    return host.submitControl(finalArgs)
}

suspend fun UsbDevice.submitTransfer(args: GeneralTransferArgs): Boolean {
    val finalArgs = args.copy(slotId = slotId)
    return host.submitTransfer(finalArgs)
}

fun UsbDevice.dispatchCompletion(event: CompletionEvent) {
    val ifaceIndex = endpointMap.get(event.endpointAddress) ?: return
    val iface = interfaces.getOrNull(ifaceIndex.toInt()) ?: return
    val driver = iface.driver ?: return
    driver.handleCompletion(event)
}
