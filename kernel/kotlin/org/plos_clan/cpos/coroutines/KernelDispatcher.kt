@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.CoroutineContext

internal const val MAX_TASKS_PER_BATCH = 64

internal interface CriticalSection {
    fun <T> withLock(block: () -> T): T
}

private class IrqCriticalSection(
    private val lock: IrqSpinLock = IrqSpinLock(),
) : CriticalSection {
    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

private class QueueDisposableHandle(
    private val disposeTask: () -> Unit,
) : DisposableHandle {
    override fun dispose() = disposeTask()
}

class KernelDispatcher internal constructor(
    private val nanoTime: () -> ULong = Hpet::nanoTime,
    private val criticalSection: CriticalSection = IrqCriticalSection(),
    private val failureReporter: (Throwable) -> Unit = Throwable::printStackTrace,
) : CoroutineDispatcher(), Delay {
    private val queue = KernelCoroutineQueue()
    private var closed = false
    private var callbacksInFlight = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        criticalSection.withLock {
            check(!closed) { CLOSED_MESSAGE }
            queue.enqueue(block)
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val handle = schedule(timeMillis, Runnable {
            with(continuation) { resumeUndispatched(Unit) }
        })
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = schedule(timeMillis, block)

    internal fun runReadyBatch(limit: Int = MAX_TASKS_PER_BATCH): Int {
        if (criticalSection.withLock { closed }) {
            return 0
        }
        val nowNanos = nanoTime()
        val ready = criticalSection.withLock {
            if (closed) {
                emptyList()
            } else {
                queue.claimReady(nowNanos, limit)
            }
        }
        var executionCount = 0
        for (runnable in ready) {
            if (!acquireExecutionToken()) {
                break
            }
            executionCount++
            try {
                try {
                    runnable.run()
                } catch (failure: Throwable) {
                    try {
                        failureReporter(failure)
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                releaseExecutionToken()
            }
        }
        return executionCount
    }

    internal fun hasReadyWork(): Boolean = criticalSection.withLock {
        !closed && queue.hasImmediateWork()
    }

    internal fun shutdown() {
        criticalSection.withLock {
            if (!closed) {
                closed = true
                queue.clear()
            }
        }
    }

    override fun toString(): String = "KernelDispatcher[BSP]"

    private fun schedule(delayMillis: Long, block: Runnable): DisposableHandle {
        criticalSection.withLock {
            check(!closed) { CLOSED_MESSAGE }
        }
        val nowNanos = nanoTime()
        val task = criticalSection.withLock {
            check(!closed) { CLOSED_MESSAGE }
            queue.schedule(nowNanos, delayMillis, block)
        }
        return QueueDisposableHandle {
            criticalSection.withLock {
                queue.dispose(task)
            }
        }
    }

    private fun acquireExecutionToken(): Boolean = criticalSection.withLock {
        if (closed) {
            false
        } else {
            callbacksInFlight++
            true
        }
    }

    private fun releaseExecutionToken() {
        criticalSection.withLock {
            check(callbacksInFlight > 0) { "callback execution token underflow" }
            callbacksInFlight--
        }
    }

    private companion object {
        const val CLOSED_MESSAGE = "KernelDispatcher is shut down"
    }
}
