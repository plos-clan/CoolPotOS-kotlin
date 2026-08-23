@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.module.Vdso
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalActionFlag
import org.plos_clan.cpos.tasks.SignalGatewayContext
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.InterruptFrame

internal object SignalGateway {
    private const val CAPTURE_SIZE = ULong.SIZE_BYTES * 3
    private const val RED_ZONE_SIZE = 128uL
    private const val GATEWAY_FLAGS = 0x202uL

    fun redirectPending(frame: InterruptFrame, thread: Thread) {
        if (!frame.cameFromUser) return
        val captureAddress = captureAddress(thread, frame.rsp, preferAlternative = false)
        val gateway = if (captureAddress == null) {
            Vdso.signalTerminateAddress
        } else {
            Vdso.signalCaptureAddress
        }
        if (gateway == 0uL) return
        val context = SignalGatewayContext.Pending(
            instructionPointer = frame.rip,
            stackPointer = frame.rsp,
            flags = frame.rflags,
            captureAddress = captureAddress,
        )
        if (!thread.signals.installGateway(context)) return
        redirect(frame, gateway, captureAddress)
    }

    fun redirectSynchronous(
        frame: InterruptFrame,
        thread: Thread,
        errorCode: ULong,
        trapNumber: ULong,
        info: SignalInfo,
    ): Boolean {
        if (!frame.cameFromUser) return false
        val action = thread.process.signals.action(info.signal)
        val caught = action.isCaught &&
            thread.signals.mask and info.signal.bit == 0uL
        val captureAddress = if (caught) {
            captureAddress(
                thread = thread,
                stackPointer = frame.rsp,
                preferAlternative = action.has(SignalActionFlag.ON_STACK),
            )
        } else {
            null
        }
        val context = SignalGatewayContext.Synchronous(
            info = info,
            instructionPointer = frame.rip,
            stackPointer = frame.rsp,
            flags = frame.rflags,
            errorCode = errorCode,
            trapNumber = trapNumber,
            captureAddress = captureAddress,
        )
        val installed = thread.signals.installGateway(context)
        if (!installed) {
            thread.signals.replaceGateway(
                context.copy(
                    info = SignalInfo(
                        signal = Signal.SEGV,
                        code = SignalInfo.SEGMENT_ACCESS_ERROR,
                        payload = SignalPayload.Fault(frame.rip),
                    ),
                    captureAddress = null,
                ),
            )
        }
        val gateway = if (installed && captureAddress != null) {
            Vdso.signalCaptureAddress
        } else {
            Vdso.signalTerminateAddress
        }
        if (gateway == 0uL) return false
        redirect(frame, gateway, captureAddress.takeIf { installed })
        return true
    }

    private fun captureAddress(
        thread: Thread,
        stackPointer: ULong,
        preferAlternative: Boolean,
    ): ULong? {
        val alternative = thread.signals.stack
        val alternativeTop = alternative.top.takeIf {
            alternative.enabled && !alternative.contains(stackPointer)
        }
        val regularTop = stackPointer.takeIf { it >= RED_ZONE_SIZE }
            ?.minus(RED_ZONE_SIZE)
        val preferred = if (preferAlternative) alternativeTop else regularTop
        val fallback = if (preferAlternative) regularTop else alternativeTop
        return writableCapture(thread, preferred) ?: writableCapture(thread, fallback)
    }

    private fun writableCapture(thread: Thread, stackTop: ULong?): ULong? {
        if (stackTop == null || stackTop < CAPTURE_SIZE.toULong()) return null
        return (stackTop - CAPTURE_SIZE.toULong()).takeIf { address ->
            UserMemory(thread.process.addressSpace, address).isWritable(CAPTURE_SIZE)
        }
    }

    private fun redirect(frame: InterruptFrame, gateway: ULong, captureAddress: ULong?) {
        frame.rip = gateway
        if (captureAddress != null) {
            frame.rsp = captureAddress + CAPTURE_SIZE.toULong()
        }
        frame.rflags = GATEWAY_FLAGS
    }
}
