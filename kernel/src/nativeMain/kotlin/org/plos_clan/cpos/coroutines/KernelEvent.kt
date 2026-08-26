package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.suspendCancellableCoroutine
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class KernelEvent internal constructor(
    private val wakeup: () -> Unit,
) {
    private val lock = IrqSpinLock()
    private var pending = false
    private var waiter: Continuation<Unit>? = null

    fun signal() {
        lock.withLock { pending = true }
        wakeup()
    }

    suspend fun await() {
        suspendCancellableCoroutine { continuation ->
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
