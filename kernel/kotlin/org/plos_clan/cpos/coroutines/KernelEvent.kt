package org.plos_clan.cpos.coroutines

import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * An IRQ-safe, coalescing wake-up for kernel coroutines.
 *
 * [signal] never resumes a continuation. The BSP dispatcher does that after the
 * hard-interrupt handler has returned, so an IRQ cannot re-enter coroutine code.
 */
class KernelEvent internal constructor() {
    private val lock = IrqSpinLock()
    private var pending = false
    private var waiter: Continuation<Unit>? = null

    fun signal() {
        lock.withLock { pending = true }
    }

    suspend fun await() {
        suspendCoroutine<Unit> { continuation ->
            val consumeNow = lock.withLock {
                if (pending) {
                    pending = false
                    true
                } else {
                    check(waiter == null) { "KernelEvent already has a waiter" }
                    waiter = continuation
                    false
                }
            }
            if (consumeNow) {
                continuation.resume(Unit)
            }
        }
    }

    internal fun dispatchPending(): Boolean {
        val continuation = lock.withLock {
            if (!pending) {
                return@withLock null
            }
            val current = waiter ?: return@withLock null
            pending = false
            waiter = null
            current
        } ?: return false

        continuation.resume(Unit)
        return true
    }
}
