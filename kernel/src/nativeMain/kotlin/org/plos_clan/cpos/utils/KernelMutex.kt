@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.utils

import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Thread
import kotlin.concurrent.atomics.AtomicLong

class KernelMutex {
    private companion object {
        const val CONTENDED = Long.MIN_VALUE
    }

    private class Waiter(val thread: Thread) {
        var acquired = false
    }

    private val stateLock = IrqSpinLock()
    private val waiters = ArrayDeque<Waiter>()
    private val owner = AtomicLong(0)

    inline fun <T> withLock(block: () -> T): T {
        lock()
        return try {
            block()
        } finally {
            unlock()
        }
    }

    inline fun <T> tryWithLock(block: () -> T): T? {
        if (!tryLock()) return null
        return try { block() } finally { unlock() }
    }

    @PublishedApi
    internal fun tryLock(): Boolean {
        val current = checkNotNull(ProcessManager.currentThread())
        return owner.compareAndSet(0, current.id.toLong() + 1)
    }

    @PublishedApi
    internal fun lock() {
        val current = checkNotNull(ProcessManager.currentThread()) {
            "A sleeping mutex requires a scheduled kernel thread"
        }
        val identity = current.id.toLong() + 1
        if (owner.compareAndSet(0, identity)) return
        val waiter = stateLock.withLock {
            while (true) {
                val observed = owner.load()
                check(observed and Long.MAX_VALUE != identity) { "KernelMutex is not reentrant" }
                val updated = if (observed == 0L) identity else observed or CONTENDED
                if (!owner.compareAndSet(observed, updated)) continue
                if (observed == 0L) return
                break
            }
            val queued = Waiter(current)
            waiters.addLast(queued)
            queued
        }

        while (true) {
            val acquired = stateLock.withLock { waiter.acquired }
            if (acquired) return
            if (!Scheduler.parkCurrent()) Scheduler.yieldCurrent()
        }
    }

    @PublishedApi
    internal fun unlock() {
        val current = checkNotNull(ProcessManager.currentThread())
        val identity = current.id.toLong() + 1
        if (owner.compareAndSet(identity, 0)) return
        val next = stateLock.withLock {
            val expected = identity or CONTENDED
            check(owner.load() == expected) { "KernelMutex unlocked by a non-owner" }
            val waiter = waiters.removeFirstOrNull()
            val successor = if (waiter == null) 0L else waiter.thread.id.toLong() + 1
            val state = if (waiters.isEmpty()) successor else successor or CONTENDED
            owner.store(state)
            if (waiter != null) waiter.acquired = true
            waiter
        }
        next?.let { Scheduler.wake(it.thread) }
    }
}
