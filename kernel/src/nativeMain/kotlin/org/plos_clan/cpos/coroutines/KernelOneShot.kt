@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.plos_clan.cpos.utils.IrqSpinLock

class KernelOneShot<T> {
    private val lock = IrqSpinLock()
    private var value: T? = null
    private var hasValue = false
    private var waiter: Continuation<T>? = null

    fun reset() = lock.withLock {
        check(waiter == null) { "Cannot reset a one-shot with a waiter" }
        value = null
        hasValue = false
    }

    fun send(value: T) {
        val continuation = lock.withLock {
            val current = waiter
            if (current == null) {
                check(!hasValue) { "One-shot already contains a value" }
                this.value = value
                hasValue = true
            } else {
                waiter = null
            }
            current
        }
        continuation?.resume(value)
    }

    suspend fun recv(): T = suspendCancellableCoroutine { continuation ->
        val result = lock.withLock {
            if (hasValue) {
                val current = value
                value = null
                hasValue = false
                current
            } else {
                check(waiter == null) { "One-shot already has a waiter" }
                waiter = continuation
                null
            }
        }
        if (result != null) continuation.resume(result)
    }
}
