@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Runnable
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

internal class KernelWorker(
    private val clock: MonotonicClock,
    private val lock: CriticalSection,
    val wake: () -> Unit,
) {
    private val queue = KernelCoroutineQueue()
    @Volatile var load = 0
        private set

    fun submit(block: Runnable) {
        val notify = lock.withLock {
            queue.enqueue(block)
            val previous = load
            load = previous + 1
            previous == 0
        }
        if (notify) wake()
    }

    fun scheduleAt(deadline: ULong, block: Runnable): DisposableHandle {
        var notify = false
        val task = lock.withLock {
            val previous = queue.nextDeadline()
            notify = previous == null || deadline < previous
            queue.scheduleAt(deadline, block)
        }
        if (notify) wake()
        return DisposableHandle { lock.withLock { queue.dispose(task) } }
    }

    fun steal(): Runnable? {
        if (load <= 1) return null
        return lock.withLock {
            val previous = load
            if (previous <= 1) return null
            val block = queue.steal() ?: return null
            load = previous - 1
            block
        }
    }

    inline fun runReadyBatch(steal: () -> Runnable?, reportFailure: (Throwable) -> Unit): Boolean {
        repeat(64) {
            val block = lock.withLock {
                val ready = queue.poll(clock::nanoTime)
                load = queue.readyCount + if (ready == null) 0 else 1
                ready
            } ?: steal() ?: return false
            try {
                block.run()
            } catch (failure: Throwable) {
                reportFailure(failure)
            }
        }
        return true
    }

    fun nextDeadline(): ULong? = lock.withLock { queue.nextDeadline() }
}

class KernelDispatcher internal constructor(
    private val clock: MonotonicClock,
    private val nextWorker: () -> KernelWorker,
    private val currentWorker: () -> KernelWorker?,
    private val newLock: () -> CriticalSection,
) : CoroutineDispatcher(), Delay {
    internal fun createEvent(): KernelEvent = KernelEvent(newLock(), this)

    private fun selectWorker(): KernelWorker {
        val local = currentWorker() ?: return nextWorker()
        val load = local.load
        if (load <= 1) return local
        val candidate = nextWorker()
        return if (candidate.load < load - 1) candidate else local
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) = selectWorker().submit(block)

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val deadline = clock.deadlineAfterMillis(timeMillis)
        val handle = scheduleAt(deadline) { with(continuation) { resumeUndispatched(Unit) } }
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        scheduleAt(clock.deadlineAfterMillis(timeMillis), block)

    internal fun scheduleAt(deadlineNanos: ULong, block: Runnable): DisposableHandle {
        val owner = currentWorker() ?: nextWorker()
        return owner.scheduleAt(deadlineNanos, block)
    }
}
