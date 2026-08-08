@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import bridge.read_cr2
import bridge.read_cr3
import bridge.register_interrupt_handler
import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.PageFaultResult
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.InterruptFrame
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isCanonicalKernelAddress
import org.plos_clan.cpos.utils.toPointer

private const val MAX_STACK_TRACE_DEPTH = 32
private val MAX_STACK_WINDOW_BYTES = 1024uL * 1024uL
private val MAX_STACK_FRAME_STEP_BYTES = 64uL * 1024uL
private const val DIVIDE_ERROR_VECTOR: UShort = 0u
private const val GENERAL_PROTECTION_VECTOR: UShort = 13u
private const val PAGE_FAULT_VECTOR: UShort = 14u
private const val PAGE_FAULT_PRESENT = 0x01uL
private const val PAGE_FAULT_WRITE = 0x02uL
private const val PAGE_FAULT_USER = 0x04uL
private const val PAGE_FAULT_RESERVED = 0x08uL
private const val PAGE_FAULT_INSTRUCTION = 0x10uL

private data class StackWindow(val rsp: ULong) {
    val isBounded: Boolean
        get() = rsp.isCanonicalKernelAddress()

    operator fun contains(address: ULong): Boolean =
        !isBounded || (address >= rsp && (address - rsp) <= MAX_STACK_WINDOW_BYTES)
}

private fun printCallStack(frame: InterruptFrame, interruptedRbp: ULong) {
    println("callStack:")
    println("  #0 ${KernelSymbolizer.describe(frame.rip)}")

    if (!interruptedRbp.isCanonicalKernelAddress()) {
        println("  <unavailable rbp=${interruptedRbp.hex()}>")
        return
    }

    val stackWindow = StackWindow(frame.rsp)
    var rbp = interruptedRbp
    var depth = 1

    while (depth < MAX_STACK_TRACE_DEPTH) {
        if ((rbp and 0x7uL) != 0uL || !rbp.isCanonicalKernelAddress()) {
            break
        }
        if (rbp !in stackWindow) {
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
        if (nextRbp !in stackWindow) {
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
    println("name=$name errorCode=${errorCode.hex()} cpu=${SMProcessor.currentLocal().lapicId}")
    println("rip=${frame.rip.hex()} cs=${frame.cs.hex()} rflags=${frame.rflags.hex()}")
    println("rsp=${frame.rsp.hex()} rbp=${interruptedRbp.hex()} ss=${frame.ss.hex()}")
    println("cr2=${read_cr2().hex()} cr3=${read_cr3().hex()}")
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

fun pageFault(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) {
    val interruptFrame = InterruptFrame(requireNotNull(frame).reinterpret())
    val cameFromUser = (ecode and PAGE_FAULT_USER) != 0uL &&
        (interruptFrame.cs and 0x3uL) == 0x3uL
    val canResolve = (ecode and PAGE_FAULT_RESERVED) == 0uL &&
        ((ecode and PAGE_FAULT_PRESENT) == 0uL ||
            (ecode and PAGE_FAULT_WRITE) != 0uL ||
            (ecode and PAGE_FAULT_INSTRUCTION) != 0uL)
    if (cameFromUser && canResolve) {
        val resolution = ProcessManager.currentProcess()
            ?.takeUnless { it.isKernelProcess }
            ?.addressSpace
            ?.faultIn(
                address = read_cr2(),
                write = (ecode and PAGE_FAULT_WRITE) != 0uL,
                execute = (ecode and PAGE_FAULT_INSTRUCTION) != 0uL,
            )
        if (resolution == PageFaultResult.RESOLVED) {
            return
        }
        println("PageFault: demand paging failed: $resolution")
    }
    haltOnFault(frame, ecode, interruptedRbp, "PageFault(#PF)")
}

fun divideError(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) =
    haltOnFault(frame, ecode, interruptedRbp, "DivideError(#DE)")

fun generalProtectionFault(frame: COpaquePointer?, ecode: ULong, interruptedRbp: ULong) =
    haltOnFault(frame, ecode, interruptedRbp, "GeneralProtectionFault(#GP)")

object ErrorHandler {
    fun initialize() {
        register_interrupt_handler(DIVIDE_ERROR_VECTOR, staticCFunction(::divideError), 0u, 142u)
        register_interrupt_handler(
            GENERAL_PROTECTION_VECTOR,
            staticCFunction(::generalProtectionFault),
            0u,
            142u,
        )
        register_interrupt_handler(PAGE_FAULT_VECTOR, staticCFunction(::pageFault), 0u, 142u)
    }
}
