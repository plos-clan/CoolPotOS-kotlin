package org.plos_clan.cpos.utils

import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread

class KernelReadWriteLock {
    private class Waiter(val thread: Thread, val exclusive: Boolean) {
        var acquired = false
    }

    private val lock = IrqSpinLock()
    private val waiters = ArrayDeque<Waiter>()
    private var readers = 0
    private var writer = false

    fun <T> withLock(exclusive: Boolean, block: () -> T): T {
        val thread = checkNotNull(ProcessManager.currentThread())
        val waiter = lock.withLock {
            if (waiters.isEmpty() && !writer && (!exclusive || readers == 0)) {
                if (exclusive) writer = true else readers++
                null
            } else Waiter(thread, exclusive).also(waiters::addLast)
        }
        if (waiter != null) {
            while (true) {
                val sequence = Scheduler.preparePark()
                if (lock.withLock { waiter.acquired }) break
                if (!Scheduler.parkCurrent(sequence)) Scheduler.yieldCurrent()
            }
        }
        return try {
            block()
        } finally {
            val ready = lock.withLock {
                if (exclusive) writer = false else readers--
                if (writer || readers != 0) return@withLock emptyList()
                val ready = mutableListOf<Waiter>()
                while (waiters.isNotEmpty() && !writer) {
                    val next = waiters.first()
                    if (next.exclusive && readers != 0) break
                    waiters.removeFirst()
                    if (next.exclusive) writer = true else readers++
                    next.acquired = true
                    ready.add(next)
                }
                ready
            }
            ready.forEach { Scheduler.wake(it.thread) }
        }
    }
}
