package org.plos_clan.cpos.utils

import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread

class KernelMutex {
    private class Waiter(val thread: Thread) {
        var acquired = false
    }

    private val stateLock = IrqSpinLock()
    private val waiters = ArrayDeque<Waiter>()
    private var owner: Thread? = null

    fun <T> withLock(block: () -> T): T {
        lock()
        return try {
            block()
        } finally {
            unlock()
        }
    }

    private fun lock() {
        val current = checkNotNull(ProcessManager.currentThread()) {
            "A sleeping mutex requires a scheduled kernel thread"
        }
        val waiter = stateLock.withLock {
            check(owner !== current) { "KernelMutex is not reentrant" }
            if (owner == null) {
                owner = current
                null
            } else {
                Waiter(current).also(waiters::addLast)
            }
        } ?: return

        while (!stateLock.withLock { waiter.acquired }) {
            if (!Scheduler.parkCurrent()) Scheduler.yieldCurrent()
        }
    }

    private fun unlock() {
        val current = checkNotNull(ProcessManager.currentThread())
        val next = stateLock.withLock {
            check(owner === current) { "KernelMutex unlocked by a non-owner" }
            waiters.removeFirstOrNull()?.also { waiter ->
                owner = waiter.thread
                waiter.acquired = true
            } ?: run {
                owner = null
                null
            }
        }
        next?.let { Scheduler.wake(it.thread) }
    }
}
