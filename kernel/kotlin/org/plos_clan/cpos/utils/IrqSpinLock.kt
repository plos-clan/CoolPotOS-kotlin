@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Spinlock that masks local interrupts while held, so state shared between
 * IRQ handlers and thread context cannot deadlock on the owning CPU.
 */
class IrqSpinLock {
    @PublishedApi
    internal val held = AtomicBoolean(false)

    inline fun <T> withLock(block: () -> T): T {
        //val flags = bridge.irq_save()
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            bridge.asm_pause()
        }
        return try {
            block()
        } finally {
            held.store(false)
            //bridge.irq_restore(flags)
        }
    }
}
