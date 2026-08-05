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

private const val MAX_TASKS_PER_BATCH = 64

private class QueueDisposableHandle(
    private val disposeTask: () -> Unit,
) : DisposableHandle {
    override fun dispose() = disposeTask()
}

class KernelDispatcher internal constructor(
    private val failureReporter: (Throwable) -> Unit,
) : CoroutineDispatcher(), Delay {
    private val lock = IrqSpinLock()
    private val queue = KernelCoroutineQueue()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.withLock { queue.enqueue(block) }
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

    internal fun runReadyBatch(): Int {
        val nowNanos = Hpet.nanoTime()
        val ready = lock.withLock {
            queue.claimReady(nowNanos, MAX_TASKS_PER_BATCH)
        }
        ready.forEach { runnable ->
            try {
                runnable.run()
            } catch (failure: Throwable) {
                try {
                    failureReporter(failure)
                } catch (_: Throwable) {
                }
            }
        }
        return ready.size
    }

    internal fun hasReadyWork(): Boolean = lock.withLock(queue::hasImmediateWork)

    override fun toString(): String = "KernelDispatcher[BSP]"

    private fun schedule(delayMillis: Long, block: Runnable): DisposableHandle {
        val nowNanos = Hpet.nanoTime()
        val task = lock.withLock {
            queue.schedule(nowNanos, delayMillis, block)
        }
        return QueueDisposableHandle {
            lock.withLock { queue.dispose(task) }
        }
    }
}
