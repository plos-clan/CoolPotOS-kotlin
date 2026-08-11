@file:OptIn(ExperimentalForeignApi::class,ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.acpi.apic.LAPIC_TIMER_FREQUENCY_HZ
import org.plos_clan.cpos.drivers.acpi.apic.LAPIC_TIMER_INTERRUPT_VECTOR
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.syscall.Syscall
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi

class CpuLocal(val lapicId: Long, val isBsp: Boolean) {
    var scheduler = PerCpuScheduler()
}

@OptIn(ExperimentalNativeApi::class)
@Suppress("unused")
@CName("kt_ap_start")
fun apStart() {
    bridge.disable_interrupt()
    val lapicId = LocalApic.localApicId
    bridge.ap_gdt_setup(lapicId.toULong())
    Syscall.initialize(lapicId.toULong(), false)
    LocalApic.enableController()
    val timerReady = LAPIC_TIMER_INTERRUPT_VECTOR <= UByte.MAX_VALUE.toUInt() &&
        LocalApic.configureDeadlineTimer(
            vector = LAPIC_TIMER_INTERRUPT_VECTOR.toUByte(),
            frequencyHz = LAPIC_TIMER_FREQUENCY_HZ,
        )
    SMProcessor.load_done.incrementAndFetch()
    if (!timerReady) {
        println("APIC: failed to configure AP $lapicId TSC-deadline timer")
        return
    }
    SMProcessor.currentLocal().scheduler.waitUntilEnabled()
    if (!Scheduler.apInitialize()) {
        return
    }
    bridge.enable_interrupt()
    bridge.fast_handoff_idle()
}

object SMProcessor {
    var cpu_count: ULong = 0U
    var load_done = AtomicInt(1)

    var locals = mutableMapOf<UInt,CpuLocal>() // <lapic_id, local_info>

    fun currentLocal() : CpuLocal {
        val local: CpuLocal = locals[LocalApic.localApicId] ?: run {
            println("error: cannot get cpu${LocalApic.localApicId} local info.")
            while (true) bridge.wait_for_interrupt()
            error("get cpu local null")
        }
        return local
    }

    fun initialize() {
        val smp = bridge.mp_request.response?.pointed ?: run {
            println("cannot find smp information.")
            return
        }
        cpu_count = smp.cpu_count
        val cpus = smp.cpus ?: run {
            println("cannot find smp information.")
            return
        }

        for (index in 0 until cpu_count.toLong()) {
            val entry = (cpus[index] ?: continue).pointed
            locals[entry.lapic_id] =
                CpuLocal(entry.lapic_id.toLong(), entry.lapic_id == smp.bsp_lapic_id)
        }

        for (index in 0 until cpu_count.toLong()) {
            val entry = (cpus[index] ?: continue).pointed
            if(entry.lapic_id == smp.bsp_lapic_id) {
                continue
            }

            val expectedLoaded = load_done.load() + 1
            val tls = bridge.__rtld_allocateTcb()
            entry.extra_argument = tls.toLong().toULong()
            entry.goto_address = bridge.ap_start_ptr

            while (load_done.load() < expectedLoaded) {
                bridge.asm_pause()
            }
        }

        while (load_done.load().toULong() < cpu_count) {
            bridge.asm_pause()
        }

        println("MultiProcessor: loaded $cpu_count cores")
    }

    fun setKernelStack(stack: ULong) {
        val local = currentLocal()
        bridge.set_kernel_stack(local.lapicId.toULong(), stack,
            (if (local.isBsp) {1} else {0}).toUByte()
        )
    }
}
