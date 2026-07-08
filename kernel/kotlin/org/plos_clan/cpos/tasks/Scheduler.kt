@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

object Scheduler {
    private val readyQueue = ArrayDeque<Thread>()
    private var currentThread: Thread? = null
    private var initialized = false
    private val scheduled = AtomicBoolean(false)

    fun enableScheduler() = scheduled.store(true)

    fun disableScheduler() = scheduled.store(false)

    fun enqueueThread(thread: Thread) {
        if (thread.isQueued) {
            return
        }
        thread.state = ThreadState.READY
        thread.isQueued = true
        readyQueue.addLast(thread)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun scheduler(regs: PtraceRegisters, irqNum: ULong) {
        if (!initialized || irqNum != 1uL || !scheduled.load()) {
            return
        }

        val running = currentThread ?: run {
            val initial = dequeueNextRunnable() ?: return
            switchTo(initial)
            if (!initial.restoreTo(regs)) {
                println("Scheduler: thread ${initial.id} has no context")
            }
            return
        }

        running.saveFrom(regs)
        if (running.state == ThreadState.RUNNING) {
            running.state = ThreadState.READY
            enqueueThread(running)
        }

        val next = dequeueNextRunnable() ?: run {
            switchTo(running)
            running.restoreTo(regs)
            return
        }

        switchTo(next)
        if (!next.restoreTo(regs)) {
            println("Scheduler: restore failed for thread ${next.id}, stay on ${running.id}")
            next.state = ThreadState.READY
            enqueueThread(next)
            switchTo(running)
            running.restoreTo(regs)
        }
    }

    fun initialize() {
        if (initialized) {
            return
        }

        currentThread = ProcessManager.getBootstrapThread()?.also { thread ->
            thread.state = ThreadState.RUNNING
        }

        ProcessManager.allThreads()
            .filter { thread -> thread !== currentThread && thread.state == ThreadState.READY }
            .forEach(::enqueueThread)

        if (!IrqController.registerAction(1, ::scheduler)) {
            println("Scheduler: failed to register timer IRQ action")
            return
        }

        initialized = true
        println("Scheduler: initialized policy=RRS current=${currentThread?.id ?: -1} queue=${readyQueue.size}")
    }

    private fun dequeueNextRunnable(): Thread? {
        while (readyQueue.isNotEmpty()) {
            val thread = readyQueue.removeFirst()
            thread.isQueued = false
            if (thread.state == ThreadState.READY) {
                return thread
            }
        }
        return null
    }

    private fun switchTo(thread: Thread) {
        currentThread = thread
        thread.state = ThreadState.RUNNING
    }
}
