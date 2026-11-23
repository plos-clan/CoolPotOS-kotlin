package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

class KernelEvent internal constructor(
    private val lock: CriticalSection,
    private val dispatcher: CoroutineDispatcher,
) : Runnable {
    private var pending = false
    private var waiter: CancellableContinuation<Unit>? = null

    fun signal() {
        val notify = lock.withLock {
            if (pending) return
            pending = true
            waiter != null
        }
        if (notify) dispatcher.dispatch(EmptyCoroutineContext, this)
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
            continuation.invokeOnCancellation {
                lock.withLock {
                    if (waiter === continuation) waiter = null
                }
            }
            if (consumeNow) {
                continuation.resume(Unit)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun run() {
        val continuation = lock.withLock {
            if (!pending) return
            val current = waiter ?: return
            pending = false
            waiter = null
            current
        }
        with(continuation) { dispatcher.resumeUndispatched(Unit) }
    }
}
