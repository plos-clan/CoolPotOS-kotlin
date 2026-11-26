@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalForeignApi::class, InternalForKotlinNative::class)

package org.plos_clan.cpos.drivers

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.CpuFeature
import org.plos_clan.cpos.utils.CpuID
import kotlin.native.internal.GCUnsafeCall
import kotlin.native.internal.InternalForKotlinNative

@GCUnsafeCall("runtime_clock_nanos")
private external fun clockNanos(): ULong

object TscClock : MonotonicClock {
    val isReady: Boolean
        get() = bridge.runtime_clock_frequency() != 0uL

    fun initialize(): Boolean {
        if (isReady) return true
        val bootFrequency = bridge.tsc_frequency_request.response?.pointed?.frequency ?: 0uL
        if (bootFrequency == 0uL) {
            println("TSC: Limine did not provide a frequency")
            return false
        }
        val supported = CpuID.has(CpuFeature.TSC_DEADLINE_TIMER)
        if (!supported || !bridge.runtime_clock_initialize(bootFrequency)) {
            println("TSC: TSC-deadline is unavailable")
            return false
        }
        println("TSC: clocksource=tsc frequency=${bootFrequency / 1_000_000uL}MHz")
        return true
    }

    override fun nanoTime(): ULong = clockNanos()
}
