@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.other

import kotlinx.cinterop.*
import org.plos_clan.cpos.utils.InterruptFrame
import org.plos_clan.cpos.utils.hex64
import org.plos_clan.cpos.utils.toPointer

private fun ULong.hex(): String = "0x${hex64()}"
private fun ULong.isCanonicalKernelAddress(): Boolean = (this shr 48) == 0xFFFFuL

private const val MAX_STACK_TRACE_DEPTH = 32
private val MAX_STACK_WINDOW_BYTES = 1024uL * 1024uL
private val MAX_STACK_FRAME_STEP_BYTES = 64uL * 1024uL

private fun isInStackWindow(address: ULong, stackBottom: ULong): Boolean {
    if (address < stackBottom) {
        return false
    }
    return (address - stackBottom) <= MAX_STACK_WINDOW_BYTES
}

private fun printCallStack(frame: InterruptFrame, interruptedRbp: ULong) {
    println("callStack:")
    println("  #0 ${KernelSymbolizer.describe(frame.rip)}")

    if (!interruptedRbp.isCanonicalKernelAddress()) {
        println("  <unavailable rbp=${interruptedRbp.hex()}>")
        return
    }

    val hasRspBoundary = frame.rsp.isCanonicalKernelAddress()
    var rbp = interruptedRbp
    var depth = 1

    while (depth < MAX_STACK_TRACE_DEPTH) {
        if ((rbp and 0x7uL) != 0uL || !rbp.isCanonicalKernelAddress()) {
            break
        }
        if (hasRspBoundary && !isInStackWindow(rbp, frame.rsp)) {
            break
        }

        val framePointer = rbp.toPointer<ULongVar>() ?: break
        val nextRbp = framePointer[0]
        val returnAddress = framePointer[1]

        if (!returnAddress.isCanonicalKernelAddress()) {
            break
        }
        println("  #$depth ${KernelSymbolizer.describe(returnAddress)}")

        if (nextRbp <= rbp) {
            break
        }
        if ((nextRbp - rbp) > MAX_STACK_FRAME_STEP_BYTES) {
            break
        }
        if (hasRspBoundary && !isInStackWindow(nextRbp, frame.rsp)) {
            break
        }

        rbp = nextRbp
        depth++
    }
}

private fun printFaultContext(
    name: String,
    frame: InterruptFrame,
    errorCode: ULong,
    interruptedRbp: ULong,
) {
    val cr2 = bridge.read_cr2()
    val cr3 = bridge.read_cr3()
    val rspAligned16 = (frame.rsp and 0xFuL) == 0uL

    println("name=$name errorCode=${errorCode.hex()}")
    println("rip=${frame.rip.hex()} cs=${frame.cs.hex()} rflags=${frame.rflags.hex()}")
    println("rsp=${frame.rsp.hex()} rbp=${interruptedRbp.hex()} ss=${frame.ss.hex()} rspAligned16=$rspAligned16")
    println("cr2=${cr2.hex()} cr3=${cr3.hex()}")
    printCallStack(frame, interruptedRbp)
}

private fun haltOnFault(
    frame: COpaquePointer?,
    errorCode: ULong,
    interruptedRbp: ULong,
    name: String,
) {
    val interruptFrame = InterruptFrame(requireNotNull(frame).reinterpret())
    printFaultContext(name, interruptFrame, errorCode, interruptedRbp)
    while (true) {}
}

fun pageFault(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) =
    haltOnFault(frame, ecode, interruptedRbp, "PageFault(#PF)")

fun divideError(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) =
    haltOnFault(frame, ecode, interruptedRbp, "DivideError(#DE)")

fun generalProtectionFault(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) =
    haltOnFault(frame, ecode, interruptedRbp, "GeneralProtectionFault(#GP)")

object ErrorHandler {
    fun initialize() {
        KernelSymbolizer.initialize()
        bridge.register_interrupt_handler(0u, staticCFunction(::divideError), 0u, 142u)
        bridge.register_interrupt_handler(13u, staticCFunction(::generalProtectionFault), 0u, 142u)
        bridge.register_interrupt_handler(14u, staticCFunction(::pageFault), 0u, 142u)
    }
}
