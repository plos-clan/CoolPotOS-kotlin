package org.plos_clan.cpos.drivers.usb.bus

enum class TransferStatus {
    COMPLETED,
    SHORT_PACKET,
    STALL,
    TRB_ERROR,
    BABBLE,
    DATA_ERROR,
    SPLIT_ERROR,
    TIMEOUT,
    DRIVER_ERROR,
    UNKNOWN,
}

data class CompletionEvent(
    val endpointAddress: UByte,
    val status: TransferStatus,
    val residualLength: UInt,
)

interface UsbDriver {
    fun disconnect()
    fun handleCompletion(event: CompletionEvent)
}

typealias ProbeFn = suspend (UsbInterface) -> UsbDriver?

val usbDrivers = mutableListOf<ProbeFn>()
