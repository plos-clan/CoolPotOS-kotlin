package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
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

        internal fun wake() {
            ready = true
            Scheduler.wake(checkNotNull(thread))
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

    fun await(lock: IrqSpinLock, waiter: Waiter): Boolean {
        val thread = checkNotNull(waiter.thread)
        var interrupted = false
        while (!lock.withLock { waiter.ready }) {
            if (thread.hasPendingSignal() || !Scheduler.parkCurrent()) {
                interrupted = true
                break
            }
        }
        lock.withLock { finish(waiter) }
        return !interrupted
    }

    fun wakeReady(available: Int) {
        val waiter = waiting.firstOrNull() ?: return
        if (available < waiter.minimum) return
        waiting.removeFirst().wake()
    }

    fun wakeOne() {
        waiting.removeFirstOrNull()?.wake()
    }

    fun wakeAll() {
        while (waiting.isNotEmpty()) waiting.removeFirst().wake()
    }
}