package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

data class ControlTransferArgs(
    val setup: SetupPacket,
    val bufferPhysicalAddress: ULong = 0uL,
)

data class GeneralTransferArgs(
    val endpointAddress: UByte = 0u,
    val bufferPhysicalAddress: ULong = 0uL,
    val length: UInt = 0u,
)

interface HostController {
    suspend fun updateEp0Mps(slotId: UByte, mps: UInt): Unit?

    suspend fun configureEndpoints(slotId: UByte, endpoints: List<UsbEndpoint>): Unit?

    suspend fun submit(slotId: UByte, transfer: UsbTransfer): Boolean

    suspend fun cancel(slotId: UByte, endpointAddress: UByte, status: TransferStatus): Boolean

    suspend fun clearHalt(slotId: UByte, endpointAddress: UByte): Boolean

    suspend fun allocateStreams(slotId: UByte, endpoints: List<UsbEndpoint>, count: Int): Int

    suspend fun disableDevice(slotId: UByte)
}

suspend fun UsbDevice.transfer(request: UsbTransfer, timeoutMillis: Long): TransferResult {
    require(timeoutMillis > 0)
    var submitted = false
    try {
        val result =
            withTimeoutOrNull(timeoutMillis) {
                submitted = host.submit(slotId, request)
                if (submitted) request.await() else TransferResult(TransferStatus.DRIVER_ERROR, 0u)
            }
        if (result != null) return result
        if (submitted)
            withContext(NonCancellable) {
                host.cancel(slotId, request.endpointAddress, TransferStatus.TIMEOUT)
            }
        return if (submitted) request.await() else TransferResult(TransferStatus.TIMEOUT, 0u)
    } finally {
        if (submitted && !request.isCompleted)
            withContext(NonCancellable) {
                host.cancel(slotId, request.endpointAddress, TransferStatus.CANCELLED)
            }
    }
}

suspend fun UsbDevice.submitControl(args: ControlTransferArgs): Unit? {
    val buffers =
        if (args.setup.length == 0u.toUShort()) emptyList()
        else listOf(UsbBuffer(args.bufferPhysicalAddress, args.setup.length.toUInt()))
    val request = UsbTransfer(0u, buffers, setup = args.setup)
    return if (transfer(request, 5_000).successful) Unit else null
}

suspend fun UsbDevice.submitTransfer(args: GeneralTransferArgs): Unit? {
    val iface = endpointOwner(args.endpointAddress) ?: return null
    val request =
        UsbTransfer(
            args.endpointAddress,
            listOf(UsbBuffer(args.bufferPhysicalAddress, args.length)),
        ) { result ->
            iface.driver?.handleCompletion(
                CompletionEvent(
                    args.endpointAddress,
                    result.status,
                    args.length - result.actualLength,
                )
            )
        }
    return if (host.submit(slotId, request)) Unit else null
}
