@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

class KernelEvent internal constructor(private val dispatcher: CoroutineDispatcher) : Runnable {
    private sealed interface State {
        data object Pending : State
        class Waiting(continuation: CancellableContinuation<Unit>) : State {
            val ready = Ready(continuation)
        }
        class Ready(val continuation: CancellableContinuation<Unit>) : State
    }

    private val state = AtomicReference<State?>(null)

    fun signal() {
        val previous = state.fetchAndUpdate { current ->
            when (current) {
                null -> State.Pending
                is State.Waiting -> current.ready
                else -> return
            }
        }
        if (previous != null) dispatcher.dispatch(EmptyCoroutineContext, this)
    }

    suspend fun await() {
        suspendCancellableCoroutine { continuation ->
            if (state.compareAndSet(State.Pending, null)) {
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val waiting = State.Waiting(continuation)
            val previous = state.fetchAndUpdate { current ->
                when (current) {
                    null -> waiting
                    State.Pending -> null
                    else -> error("KernelEvent already has a waiter")
                }
            }
            if (previous === State.Pending) continuation.resume(Unit)
            else continuation.invokeOnCancellation {
                if (!state.compareAndSet(waiting, null)) state.compareAndSet(waiting.ready, State.Pending)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun run() {
        val ready = state.load() as? State.Ready ?: return
        if (!state.compareAndSet(ready, null)) return
        with(ready.continuation) { dispatcher.resumeUndispatched(Unit) }
    }
}
