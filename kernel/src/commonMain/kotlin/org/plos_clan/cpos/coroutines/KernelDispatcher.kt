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
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.coroutines.CoroutineContext

private const val MAX_TASKS_PER_BATCH = 64

private class QueueDisposableHandle(
    private val disposeTask: () -> Unit,
) : DisposableHandle {
    override fun dispose() = disposeTask()
}

class KernelDispatcher internal constructor(
    private val clock: MonotonicClock,
    private val lock: CriticalSection,
    private val wakeup: () -> Unit,
    private val failureReporter: (Throwable) -> Unit,
) : CoroutineDispatcher(), Delay {
    private val queue = KernelCoroutineQueue()
    private val events = mutableListOf<KernelEvent>()

    internal fun createEvent(): KernelEvent = KernelEvent(lock, wakeup).also { event ->
        lock.withLock { events += event }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.withLock { queue.enqueue(block) }
        wakeup()
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val handle = schedule(timeMillis) {
            with(continuation) { resumeUndispatched(Unit) }
        }
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = schedule(timeMillis, block)

    internal fun runReadyBatch(): Int {
        dispatchPendingEvents()
        val nowNanos = clock.nanoTime()
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

    internal fun nextDeadlineNanos(): ULong? = lock.withLock(queue::nextDeadline)

    private fun dispatchPendingEvents() {
        events.forEach { event ->
            try {
                event.dispatchPending()
            } catch (failure: Throwable) {
                failureReporter(failure)
            }
        }
    }

    override fun toString(): String = "KernelDispatcher[BSP]"

    private fun schedule(delayMillis: Long, block: Runnable): DisposableHandle {
        val nowNanos = clock.nanoTime()
        val task = lock.withLock {
            queue.schedule(nowNanos, delayMillis, block)
        }
        return cancellationHandle(task)
    }

    internal fun scheduleAt(deadlineNanos: ULong, block: Runnable): DisposableHandle {
        val task = lock.withLock { queue.scheduleAt(deadlineNanos, block) }
        return cancellationHandle(task)
    }

    private fun cancellationHandle(task: DelayedCoroutineTask): DisposableHandle {
        wakeup()
        return QueueDisposableHandle {
            if (lock.withLock { queue.dispose(task) }) wakeup()
        }
    }

}
