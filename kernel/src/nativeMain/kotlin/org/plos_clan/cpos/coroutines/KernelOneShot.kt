@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.suspendCancellableCoroutine
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

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

    fun trySend(value: T): Boolean {
        var accepted = true
        val continuation = lock.withLock {
            val current = waiter
            if (current == null) {
                if (hasValue) {
                    accepted = false
                    return@withLock null
                }
                this.value = value
                hasValue = true
            } else {
                waiter = null
            }
            current
        }
        continuation?.resume(value)
        return accepted
    }

    fun send(value: T) {
        check(trySend(value)) { "One-shot already contains a value" }
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
