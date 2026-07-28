@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class PerCpuScheduler {
    val readyQueue = ArrayDeque<Thread>()
    var currentThread: Thread? = null
    val scheduled = AtomicBoolean(false)
    var initialized = false
}

object Scheduler {
    private val scheduled = AtomicBoolean(false)

    fun enableScheduler() = scheduled.store(true)

    fun disableScheduler() = scheduled.store(false)

    fun enqueueThread(thread: Thread) {
        if (thread.isQueued) {
            return
        }
        thread.state = TaskState.READY
        thread.isQueued = true
        SMProcessor.currentLocal().scheduler.readyQueue.addLast(thread)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun scheduler(regs: PtraceRegisters, irqNum: ULong) {
        if (!SMProcessor.currentLocal().scheduler.initialized || irqNum != 1uL || !scheduled.load()) {
            return
        }

        val running = SMProcessor.currentLocal().scheduler.currentThread ?: run {
            val initial = dequeueNextRunnable() ?: return
            switchTo(initial)
            if (!initial.restoreTo(regs)) {
                println("Scheduler: thread ${initial.id} has no context")
            }
            return
        }

        running.saveFrom(regs)
        if (running.state == TaskState.RUNNING) {
            running.state = TaskState.READY
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
            next.state = TaskState.READY
            enqueueThread(next)
            switchTo(running)
            running.restoreTo(regs)
        }
    }

    fun apInitialize() {

    }

    fun initialize() {
        if (SMProcessor.currentLocal().scheduler.initialized) {
            return
        }

        SMProcessor.currentLocal().scheduler.currentThread =
            ProcessManager.getBootstrapThread()?.also { thread ->
                thread.state = TaskState.RUNNING
            }

        ProcessManager.allThreads()
            .filter { thread -> thread !== SMProcessor.currentLocal().scheduler.currentThread && thread.state == TaskState.READY }
            .forEach(::enqueueThread)

        if (!IrqController.registerAction(1, ::scheduler)) {
            println("Scheduler: failed to register timer IRQ action")
            return
        }

        SMProcessor.currentLocal().scheduler.initialized = true
        println("Scheduler: initialized policy=RRS core=${SMProcessor.currentLocal().lapicId} queue=${SMProcessor.currentLocal().scheduler.readyQueue.size}")
    }

    private fun dequeueNextRunnable(): Thread? {
        while (SMProcessor.currentLocal().scheduler.readyQueue.isNotEmpty()) {
            val thread = SMProcessor.currentLocal().scheduler.readyQueue.removeFirst()
            thread.isQueued = false
            if (thread.state == TaskState.READY) {
                return thread
            }
        }
        return null
    }

    private fun switchTo(thread: Thread) {
        SMProcessor.currentLocal().scheduler.currentThread = thread
        thread.state = TaskState.RUNNING
    }
}
