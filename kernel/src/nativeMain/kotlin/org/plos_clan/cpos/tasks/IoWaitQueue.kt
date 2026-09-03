package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.IrqSpinLock

internal class IoWaitQueue {
    class Waiter internal constructor() {
        internal var minimum = 1
            private set
        internal var thread: Thread? = null
            private set
        internal var ready = false
            private set

        internal fun arm(minimum: Int, thread: Thread) {
            this.minimum = minimum
            this.thread = thread
            ready = false
        }

        internal fun markReady(): Thread {
            ready = true
            return checkNotNull(thread)
        }

        internal fun recycle() {
            thread = null
        }
    }

    private val waiting = ArrayDeque<Waiter>()
    private val recycled = ArrayDeque<Waiter>()

    fun add(thread: Thread, minimum: Int = 1): Waiter {
        require(minimum >= 0)
        return (recycled.removeFirstOrNull() ?: Waiter()).also { waiter ->
            waiter.arm(minimum, thread)
            waiting.addLast(waiter)
        }
    }

    private fun finish(waiter: Waiter) {
        if (!waiter.ready) check(waiting.remove(waiter))
        waiter.recycle()
        recycled.addLast(waiter)
    }

    fun await(lock: IrqSpinLock, waiter: Waiter, deadlineNanos: ULong? = null): Boolean {
        val thread = checkNotNull(waiter.thread)
        var interrupted = false
        while (!lock.withLock { waiter.ready } &&
            (deadlineNanos == null || TscClock.nanoTime() < deadlineNanos)
        ) {
            if (thread.hasPendingSignal()) {
                interrupted = true
                break
            }
            val parked = if (deadlineNanos == null) {
                Scheduler.parkCurrent()
            } else {
                Scheduler.parkCurrentUntil(deadlineNanos)
            }
            if (!parked) {
                interrupted = true
                break
            }
        }
        lock.withLock { finish(waiter) }
        return !interrupted
    }

    fun wakeReady(available: Int) {
        takeReady(available)?.let(Scheduler::wake)
    }

    fun wakeOne() {
        takeOne()?.let(Scheduler::wake)
    }

    fun wakeAll() {
        while (true) Scheduler.wake(takeOne() ?: return)
    }

    fun takeAll(): List<Thread> {
        if (waiting.isEmpty()) return emptyList()
        return ArrayList<Thread>(waiting.size).also { result ->
            while (waiting.isNotEmpty()) result += checkNotNull(takeOne())
        }
    }

    fun takeReady(available: Int): Thread? {
        val waiter = waiting.firstOrNull() ?: return null
        if (available < waiter.minimum) return null
        waiting.removeFirst()
        return waiter.markReady()
    }

    fun takeOne(): Thread? = waiting.removeFirstOrNull()?.markReady()
}
