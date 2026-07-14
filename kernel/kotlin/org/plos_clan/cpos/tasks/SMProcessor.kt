@file:OptIn(ExperimentalForeignApi::class,ExperimentalNativeApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.apic.LAPIC_TIMER_FREQUENCY_HZ
import org.plos_clan.cpos.drivers.apic.LAPIC_TIMER_INTERRUPT_VECTOR
import org.plos_clan.cpos.drivers.apic.LocalApic
import org.plos_clan.cpos.drivers.apic.LocalApic.calibrateTimer
import org.plos_clan.cpos.drivers.apic.LocalApic.configurePeriodicTimer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi

class CpuLocal(val lapicId: Long) {
    var scheduler = PerCpuScheduler()
}

@OptIn(ExperimentalNativeApi::class)
@Suppress("unused")
@CName("kt_ap_start")
fun apStart() {
    bridge.disable_interrupt()
    val lapicId = LocalApic.destinationApicId
    SMProcessor.locals[lapicId] = CpuLocal(lapicId.toLong())
    LocalApic.enableController()
    val timerInitialCount = calibrateTimer(LAPIC_TIMER_FREQUENCY_HZ)
    if (timerInitialCount != 0uL && LAPIC_TIMER_INTERRUPT_VECTOR <= UByte.MAX_VALUE.toUInt()) {
        configurePeriodicTimer(
            vector = LAPIC_TIMER_INTERRUPT_VECTOR.toUByte(),
            initialCount = timerInitialCount,
            masked = false,
        )
    }
    SMProcessor.load_done.incrementAndFetch()
    Scheduler.apInitialize()
   // bridge.enable_interrupt()
    while(true) bridge.wait_for_interrupt()
}

object SMProcessor {
    var cpu_count: ULong = 0U
    var load_done = AtomicInt(1)

    var locals = mutableMapOf<UInt,CpuLocal>() // <lapic_id, local_info>

    fun currentLocal() : CpuLocal {
        val local: CpuLocal = locals[LocalApic.destinationApicId] ?: run {
            println("error: cannot get cpu${LocalApic.destinationApicId} local info.")
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

        locals[smp.bsp_lapic_id] = CpuLocal(smp.bsp_lapic_id.toLong())

        for (index in 0 until cpu_count.toLong()) {
            val entry = (cpus[index] ?: continue).pointed
            if(entry.lapic_id == smp.bsp_lapic_id) {
                continue
            }
            val tls = bridge.__rtld_allocateTcb()
            entry.extra_argument = tls.toLong().toULong()
            entry.goto_address = bridge.ap_start_ptr
        }

        while (load_done.load().toULong() < cpu_count) {
            bridge.asm_pause()
        }

        println("MultiProcessor: loaded $cpu_count cores")
    }
}