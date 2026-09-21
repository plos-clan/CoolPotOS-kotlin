package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.IrqSpinLock

internal class IoWaitQueue {
    val events = PollSource()
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
        val waiter = recycled.removeFirstOrNull() ?: Waiter()
        waiter.arm(minimum, thread)
        waiting.addLast(waiter)
        return waiter
    }

    private fun finish(waiter: Waiter) {
        if (!waiter.ready) check(waiting.remove(waiter))
        waiter.recycle()
        recycled.addLast(waiter)
    }

    fun await(lock: IrqSpinLock, waiter: Waiter, deadlineNanos: ULong? = null): Boolean {
        val thread = checkNotNull(waiter.thread)
        var interrupted = false
        while (true) {
            val sequence = Scheduler.preparePark()
            val ready = lock.withLock { waiter.ready }
            if (ready) break
            if (deadlineNanos != null && TscClock.nanoTime() >= deadlineNanos) break
            if (thread.hasPendingSignal()) {
                interrupted = true
                break
            }
            val parked = if (deadlineNanos == null) {
                Scheduler.parkCurrent(sequence)
            } else {
                Scheduler.parkCurrentUntil(deadlineNanos, sequence)
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
        events.signal()
        takeReady(available)?.let(Scheduler::wake)
    }

    fun wakeOne() {
        events.signal()
        takeOne()?.let(Scheduler::wake)
    }

    fun wakeAll() {
        events.signal()
        while (true) {
            val thread = takeOne() ?: return
            Scheduler.wake(thread)
        }
    }

    fun takeAll(): List<Thread> {
        events.signal()
        if (waiting.isEmpty()) return emptyList()
        val result = ArrayList<Thread>(waiting.size)
        while (true) {
            val thread = takeOne() ?: return result
            result.add(thread)
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
