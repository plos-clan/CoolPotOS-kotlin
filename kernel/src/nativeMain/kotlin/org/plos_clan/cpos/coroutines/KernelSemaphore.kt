@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.suspendCancellableCoroutine
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class KernelSemaphore(initialPermits: Int) {
    private val lock = IrqSpinLock()
    private var permits = initialPermits
    private val waiters = ArrayDeque<Continuation<Unit>>()

    init {
        require(initialPermits >= 0)
    }

    fun release() {
        val waiter = lock.withLock {
            waiters.removeFirstOrNull() ?: run {
                permits++
                return@withLock null
            }
        }
        waiter?.resume(Unit)
    }

    suspend fun acquire() {
        suspendCancellableCoroutine { continuation ->
            val acquireNow = lock.withLock {
                if (permits > 0) {
                    permits--
                    true
                } else {
                    waiters.addLast(continuation)
                    false
                }
            }
            if (acquireNow) continuation.resume(Unit)
        }
    }
}
