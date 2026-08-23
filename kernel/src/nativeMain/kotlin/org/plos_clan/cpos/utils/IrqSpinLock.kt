@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi

class IrqSpinLock {
    @PublishedApi
    internal val held = AtomicBoolean(false)

    inline fun <T> withLock(block: () -> T): T {
        val flags = bridge.irq_save()
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            bridge.asm_pause()
        }
        return try {
            block()
        } finally {
            held.store(false)
            bridge.irq_restore(flags)
        }
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
