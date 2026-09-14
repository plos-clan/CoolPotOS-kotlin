@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class IrqSpinLock : CriticalSection() {
    @PublishedApi
    internal val held = AtomicBoolean(false)

    override fun acquire(): ULong {
        val flags = bridge.irq_save()
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            if (!bridge.fast_handoff_yield()) bridge.asm_pause()
        }
        return flags
    }

    override fun release(state: ULong) {
        held.store(false)
        bridge.irq_restore(state)
    }

    inline fun tryWithLock(block: () -> Unit): Boolean {
        val flags = bridge.irq_save()
        if (!held.compareAndSet(expectedValue = false, newValue = true)) {
            bridge.irq_restore(flags)
            return false
        }
        try {
            block()
        } finally {
            held.store(false)
            bridge.irq_restore(flags)
        }
        return true
    }
}
