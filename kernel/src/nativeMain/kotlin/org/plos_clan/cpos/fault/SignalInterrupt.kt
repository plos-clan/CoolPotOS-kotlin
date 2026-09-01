@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package org.plos_clan.cpos.fault

import bridge.register_interrupt_handler
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import org.plos_clan.cpos.drivers.acpi.apic.LAPIC_TIMER_INTERRUPT_VECTOR
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.syscall.SignalGateway
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.SignalPreemption
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.InterruptFrame
import kotlin.concurrent.atomics.AtomicInt

internal object SignalInterrupt : SignalPreemption {
    private const val VECTOR: UShort = 31u

    fun initialize() {
        register_interrupt_handler(VECTOR, staticCFunction(::signalInterrupt), 0u, 142u)
        SignalRouter.installPreemption(this)
    }

    override fun request(thread: Thread) {
        val lapicId = thread.scheduledLapicId ?: return
        val cpu = SMProcessor.locals[lapicId] ?: return
        val load = bridge.fast_handoff_cpu_load(lapicId.toULong())
        if (load == 0uL || load == ULong.MAX_VALUE) return
        val requested = minOf(load, Int.MAX_VALUE.toULong()).toInt()
        val passes = cpu.scheduler.signalPasses
        while (true) {
            val current = passes.load()
            if (current >= requested || passes.compareAndSet(current, requested)) break
        }
        LocalApic.sendFixedInterrupt(lapicId, VECTOR.toUByte())
    }

    fun handle(frame: InterruptFrame) {
        LocalApic.endOfInterrupt()
        if (frame.cameFromUser) {
            val thread = ProcessManager.currentThread()
            if (thread != null && thread.hasPendingSignal()) {
                SignalGateway.redirectPending(frame, thread)
            }
        }

        val local = SMProcessor.currentLocal()
        if (consumePass(local.scheduler.signalPasses)) {
            val lapicId = local.lapicId.toUInt()
            LocalApic.sendFixedInterrupt(lapicId, LAPIC_TIMER_INTERRUPT_VECTOR.toUByte())
            LocalApic.sendFixedInterrupt(lapicId, VECTOR.toUByte())
        }
    }

    private fun consumePass(passes: AtomicInt): Boolean {
        while (true) {
            val remaining = passes.load()
            if (remaining <= 1) {
                if (remaining == 0 || passes.compareAndSet(remaining, 0)) return false
            } else if (passes.compareAndSet(remaining, remaining - 1)) {
                return true
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun signalInterrupt(
    frame: COpaquePointer?,
    errorCode: ULong,
    interruptedRbp: ULong,
    faultAddress: ULong,
) {
    SignalInterrupt.handle(InterruptFrame(requireNotNull(frame).reinterpret()))
}
