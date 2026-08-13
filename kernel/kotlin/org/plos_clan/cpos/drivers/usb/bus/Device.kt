package org.plos_clan.cpos.drivers.usb.bus

class UsbDevice(
    val host: HostController,
    val slotId: UByte,
    val portId: Int,
    val speed: UInt,
) {
    suspend fun enumerate(): Boolean {
        println("USB: enumeration of slot $slotId is not implemented yet")
        return false
    }

    fun free() {}

    fun dispatchCompletion(event: CompletionEvent) {}
}
