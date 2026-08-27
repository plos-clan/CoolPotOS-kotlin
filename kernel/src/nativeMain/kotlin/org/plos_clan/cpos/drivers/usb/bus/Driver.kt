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
    ;

    val successful: Boolean
        get() = this == COMPLETED || this == SHORT_PACKET
}

data class CompletionEvent(
    val endpointAddress: UByte,
    val status: TransferStatus,
    val residualLength: UInt,
)

interface UsbDriver {
    fun quiesce() {}
    suspend fun disconnect()
    fun handleCompletion(event: CompletionEvent)
}

typealias ProbeFn = suspend (UsbInterface) -> UsbDriver?

val usbDrivers = mutableListOf<ProbeFn>()
