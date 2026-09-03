@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.drivers.TscClock
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
    private val events = mutableListOf<KernelEvent>()

    internal fun createEvent(): KernelEvent = KernelEvent(::wake).also { event ->
        lock.withLock { events += event }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.withLock { queue.enqueue(block) }
        wake()
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
        val nowNanos = TscClock.nanoTime()
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
        val nowNanos = TscClock.nanoTime()
        val task = lock.withLock {
            queue.schedule(nowNanos, delayMillis, block)
        }
        wake()
        return QueueDisposableHandle {
            if (lock.withLock { queue.dispose(task) }) wake()
        }
    }

    private fun wake() = bridge.fast_handoff_wake_bsp()
}
