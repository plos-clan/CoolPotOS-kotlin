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

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        criticalSection.withLock {
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

    fun runReadyBatch(limit: Int = MAX_TASKS_PER_BATCH): Int {
        val nowNanos = nanoTime()
        val ready = criticalSection.withLock {
            queue.claimReady(nowNanos, limit)
        }
        ready.forEach { runnable ->
            try {
                runnable.run()
            } catch (failure: Throwable) {
                failureReporter(failure)
            }
        }
        return ready.size
    }

    fun hasReadyWork(): Boolean = criticalSection.withLock(queue::hasImmediateWork)

    override fun toString(): String = "KernelDispatcher[BSP]"

    private fun schedule(delayMillis: Long, block: Runnable): DisposableHandle {
        val nowNanos = nanoTime()
        val task = criticalSection.withLock {
            queue.schedule(nowNanos, delayMillis, block)
        }
        return QueueDisposableHandle {
            criticalSection.withLock {
                queue.dispose(task)
            }
        }
    }
}
