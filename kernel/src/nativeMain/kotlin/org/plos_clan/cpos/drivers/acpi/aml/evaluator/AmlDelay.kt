@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml.evaluator

import bridge.asm_pause
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock

internal object AmlDelay {
    fun wait(microseconds: ULong): Boolean {
        if (microseconds > MAX_AML_DELAY_MICROSECONDS) {
            return false
        }
        val duration = microseconds * 1_000uL
        if (duration == 0uL) {
            return true
        }
        if (!TscClock.isReady) {
            return false
        }
        val start = TscClock.nanoTime()
        while (TscClock.nanoTime() - start < duration) {
            asm_pause()
        }
        return true
    }
}
