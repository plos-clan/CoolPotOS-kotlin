@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import bridge.kotlin_exception_callbacks
import bridge.read_cr3
import bridge.register_interrupt_handler
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.mem.addressspace.PageFaultResult
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.syscall.SignalGateway
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.utils.InterruptFrame
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isCanonicalKernelAddress
import org.plos_clan.cpos.utils.toPointer
import kotlin.native.internal.ExportForCppRuntime

private const val MAX_STACK_TRACE_DEPTH = 32
private val MAX_STACK_WINDOW_BYTES = 1024uL * 1024uL
private val MAX_STACK_FRAME_STEP_BYTES = 64uL * 1024uL
private const val PAGE_FAULT_VECTOR: UShort = 14u
private const val PAGE_FAULT_PRESENT = 0x01uL
private const val PAGE_FAULT_WRITE = 0x02uL
private const val PAGE_FAULT_USER = 0x04uL
private const val PAGE_FAULT_RESERVED = 0x08uL
private const val PAGE_FAULT_INSTRUCTION = 0x10uL

private enum class UserException(
    val vector: UShort,
    val signal: Signal,
    val code: Int,
    val displayName: String,
    val reportsInstruction: Boolean = true,
    val instructionOffset: ULong = 0uL,
) {
    DIVIDE(0u, Signal.FLOATING_POINT_EXCEPTION, SignalInfo.INTEGER_DIVIDE_BY_ZERO, "DivideError(#DE)"),
    DEBUG(1u, Signal.TRAP, SignalInfo.TRAP_TRACE, "DebugException(#DB)"),
    BREAKPOINT(
        3u,
        Signal.TRAP,
        SignalInfo.TRAP_BREAKPOINT,
        "Breakpoint(#BP)",
        instructionOffset = 1uL,
    ),
    OVERFLOW(4u, Signal.SEGV, SignalInfo.KERNEL, "Overflow(#OF)", false),
    BOUNDS(5u, Signal.SEGV, SignalInfo.KERNEL, "BoundsCheck(#BR)", false),
    INVALID_OPCODE(6u, Signal.ILLEGAL_INSTRUCTION, SignalInfo.ILLEGAL_OPERAND, "InvalidOpcode(#UD)"),
    GENERAL_PROTECTION(13u, Signal.SEGV, SignalInfo.KERNEL, "GeneralProtectionFault(#GP)", false),
    X87_FLOATING_POINT(
        16u,
        Signal.FLOATING_POINT_EXCEPTION,
        SignalInfo.FLOATING_INVALID_OPERATION,
        "X87FloatingPoint(#MF)",
    ),
    ALIGNMENT(17u, Signal.BUS, SignalInfo.BUS_ALIGNMENT_ERROR, "AlignmentCheck(#AC)", false),
    SIMD_FLOATING_POINT(
        19u,
        Signal.FLOATING_POINT_EXCEPTION,
        SignalInfo.FLOATING_INVALID_OPERATION,
        "SimdFloatingPoint(#XM)",
    ),
}

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
    faultAddress: ULong,
) {
    println("name=$name errorCode=${errorCode.hex()} cpu=${SMProcessor.currentLocal().lapicId}")
    println("rip=${frame.rip.hex()} cs=${frame.cs.hex()} rflags=${frame.rflags.hex()}")
    println("rsp=${frame.rsp.hex()} rbp=${interruptedRbp.hex()} ss=${frame.ss.hex()}")
    println("cr2=${faultAddress.hex()} cr3=${read_cr3().hex()}")
    printCallStack(frame, interruptedRbp)
}

private fun haltOnFault(
    frame: InterruptFrame,
    errorCode: ULong,
    interruptedRbp: ULong,
    faultAddress: ULong,
    name: String,
) {
    printFaultContext(name, frame, errorCode, interruptedRbp, faultAddress)
    while (true) {
        bridge.wait_for_interrupt()
    }
}

private fun redirectUserSignal(
    frame: InterruptFrame,
    errorCode: ULong,
    trapNumber: ULong,
    info: SignalInfo,
): Boolean {
    if (!frame.cameFromUser) return false
    val thread = ProcessManager.currentThread()
        ?.takeUnless { it.process.isKernelProcess } ?: return false
    return SignalGateway.redirectSynchronous(frame, thread, errorCode, trapNumber, info)
}

fun pageFault(
    frame: COpaquePointer?,
    ecode: ULong,
    interruptedRbp: ULong,
    faultAddress: ULong,
) {
    val interruptFrame = InterruptFrame(requireNotNull(frame).reinterpret())
    val cameFromUser = (ecode and PAGE_FAULT_USER) != 0uL &&
        interruptFrame.cameFromUser
    val canResolve = (ecode and PAGE_FAULT_RESERVED) == 0uL &&
        ((ecode and PAGE_FAULT_PRESENT) == 0uL ||
            (ecode and PAGE_FAULT_WRITE) != 0uL ||
            (ecode and PAGE_FAULT_INSTRUCTION) != 0uL)
    var resolution: PageFaultResult? = null
    if (canResolve) {
        val write = (ecode and PAGE_FAULT_WRITE) != 0uL
        val execute = (ecode and PAGE_FAULT_INSTRUCTION) != 0uL
        resolution = if (cameFromUser) {
            ProcessManager.currentProcess()
                ?.takeUnless { it.isKernelProcess }
                ?.addressSpace
                ?.faultIn(faultAddress, write, execute)
        } else {
            KernelPageDirectory.addressSpace.faultIn(faultAddress, write, execute)
        }
        if (resolution == PageFaultResult.RESOLVED) {
            return
        }
    }
    if (cameFromUser && resolution == PageFaultResult.INTERRUPTED) {
        val thread = ProcessManager.currentThread() ?: return
        if (thread.hasPendingSignal()) SignalGateway.redirectPending(interruptFrame, thread)
        return
    }
    if (canResolve && !cameFromUser) println("PageFault: demand paging failed: $resolution")
    val info = if (resolution == PageFaultResult.IO_ERROR) {
        SignalInfo(
            signal = Signal.BUS,
            code = SignalInfo.BUS_OBJECT_ERROR,
            payload = SignalPayload.Fault(faultAddress),
        )
    } else {
        SignalInfo(
            signal = Signal.SEGV,
            code = if (resolution == PageFaultResult.ACCESS_DENIED ||
                ecode and PAGE_FAULT_PRESENT != 0uL
            ) {
                SignalInfo.SEGMENT_ACCESS_ERROR
            } else {
                SignalInfo.SEGMENT_MAPPING_ERROR
            },
            payload = SignalPayload.Fault(faultAddress),
        )
    }
    if (cameFromUser && redirectUserSignal(
            frame = interruptFrame,
            errorCode = ecode,
            trapNumber = PAGE_FAULT_VECTOR.toULong(),
            info = info,
        )
    ) return
    haltOnFault(interruptFrame, ecode, interruptedRbp, faultAddress, "PageFault(#PF)")
}

private fun handleUserException(
    frame: COpaquePointer?,
    errorCode: ULong,
    interruptedRbp: ULong,
    faultAddress: ULong,
    exception: UserException,
) {
    val interruptFrame = InterruptFrame(requireNotNull(frame).reinterpret())
    val payload = if (exception.reportsInstruction) {
        val instruction = interruptFrame.rip
        SignalPayload.Fault(instruction - minOf(instruction, exception.instructionOffset))
    } else {
        SignalPayload.None
    }
    if (redirectUserSignal(
            frame = interruptFrame,
            errorCode = errorCode,
            trapNumber = exception.vector.toULong(),
            info = SignalInfo(
                signal = exception.signal,
                code = exception.code,
                payload = payload,
            ),
        )
    ) return
    haltOnFault(interruptFrame, errorCode, interruptedRbp, faultAddress, exception.displayName)
}

@ExportForCppRuntime("kotlin_handle_exception")
fun handleException(
    frame: COpaquePointer?,
    errorCode: ULong,
    rbp: ULong,
    faultAddress: ULong,
    vector: Int,
) {
    if (vector == PAGE_FAULT_VECTOR.toInt()) {
        pageFault(frame, errorCode, rbp, faultAddress)
        return
    }
    val exception = UserException.entries.first { it.vector.toInt() == vector }
    handleUserException(frame, errorCode, rbp, faultAddress, exception)
}

object ErrorHandler {
    fun initialize() {
        for (exception in UserException.entries) {
            register_interrupt_handler(
                exception.vector,
                kotlin_exception_callbacks[exception.vector.toInt()],
                0u,
                if (exception == UserException.BREAKPOINT) 238u else 142u,
            )
        }
        register_interrupt_handler(
            PAGE_FAULT_VECTOR,
            kotlin_exception_callbacks[PAGE_FAULT_VECTOR.toInt()],
            0u,
            142u,
        )
        SignalInterrupt.initialize()
    }
}
