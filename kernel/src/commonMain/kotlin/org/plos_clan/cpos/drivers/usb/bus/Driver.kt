package org.plos_clan.cpos.drivers.usb.bus

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
