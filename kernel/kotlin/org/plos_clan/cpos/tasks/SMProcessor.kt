@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.apic.LocalApic
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@Suppress("unused")
@CName("kt_ap_start")
fun apStart() {
    val lapic_id = LocalApic.destinationApicId
    println("multi-processor: lapic=$lapic_id")
    SMProcessor.load_done.incrementAndFetch()
    while(true) bridge.wait_for_interrupt()
}

object SMProcessor {
    var cpu_count: ULong = 0U
    var load_done = AtomicInt(1)

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
            if(entry.lapic_id == smp.bsp_lapic_id) {
                continue
            }
            val tls = bridge.__rtld_allocateTcb()
            entry.extra_argument = tls.toLong().toULong()
            entry.goto_address = bridge.ap_start_ptr
        }

        while (load_done.load().toULong() < cpu_count);

        println("MultiProcessor: loaded $cpu_count cores")
    }
}