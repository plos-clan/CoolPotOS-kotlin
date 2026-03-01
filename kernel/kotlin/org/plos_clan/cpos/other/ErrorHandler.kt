@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.other

import kotlinx.cinterop.*
import org.plos_clan.cpos.utils.InterruptFrame
import org.plos_clan.cpos.utils.hex64

private fun ULong.hex(): String = "0x${hex64()}"

private fun printFaultContext(
    name: String,
    frame: InterruptFrame,
    errorCode: ULong,
) {
    val cr2 = bridge.read_cr2()
    val cr3 = bridge.read_cr3()
    val rspAligned16 = (frame.rsp and 0xFuL) == 0uL

    println("name=$name errorCode=${errorCode.hex()}")
    println("rip=${frame.rip.hex()} cs=${frame.cs.hex()} rflags=${frame.rflags.hex()}")
    println("rsp=${frame.rsp.hex()} ss=${frame.ss.hex()} rspAligned16=$rspAligned16")
    println("cr2=${cr2.hex()} cr3=${cr3.hex()}")
}

private fun haltOnFault(
    frame: COpaquePointer?,
    errorCode: ULong,
    name: String,
) {
    val interruptFrame = InterruptFrame(requireNotNull(frame).reinterpret())
    printFaultContext(name, interruptFrame, errorCode)
    while (true) {}
}

fun pageFault(frame: COpaquePointer?, ecode: ULong) =
    haltOnFault(frame, ecode, "PageFault(#PF)")

fun divideError(frame: COpaquePointer?, ecode: ULong) =
    haltOnFault(frame, ecode, "DivideError(#DE)")

fun generalProtectionFault(frame: COpaquePointer?, ecode: ULong) =
    haltOnFault(frame, ecode, "GeneralProtectionFault(#GP)")

object ErrorHandler {
    fun initialize() {
        bridge.register_interrupt_handler(0u, staticCFunction(::divideError), 0u, 142u)
        bridge.register_interrupt_handler(13u, staticCFunction(::generalProtectionFault), 0u, 142u)
        bridge.register_interrupt_handler(14u, staticCFunction(::pageFault), 0u, 142u)
    }
}
