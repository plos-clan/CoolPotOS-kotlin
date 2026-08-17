@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import kotlinx.cinterop.pointed

object TscClock {
    val isReady: Boolean
        get() = bridge.runtime_clock_frequency() != 0uL

    fun initialize(): Boolean {
        if (isReady) return true
        val bootFrequency = bridge.tsc_frequency_request.response?.pointed?.frequency ?: 0uL
        if (bootFrequency == 0uL) {
            println("TSC: Limine did not provide a frequency")
            return false
        }
        val frequency = bridge.runtime_clock_initialize(bootFrequency)
        if (frequency == 0uL) {
            println("TSC: TSC-deadline is unavailable")
            return false
        }
        println("TSC: clocksource=tsc frequency=${frequency / 1_000_000uL}MHz")
        return true
    }

    fun nanoTime(): ULong = bridge.runtime_clock_nanos()
}
