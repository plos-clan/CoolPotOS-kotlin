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

    fun waitUntilEnabled() {
        while (!scheduled.load()) {
            bridge.asm_pause()
        }
    }
}

object Scheduler {
    private val scheduled = AtomicBoolean(false)
    private var irqRegistered = false
    private var nextCpuIndex = 0

    fun enableScheduler() {
        prepareApBootstrapThreads()

        val localScheduler = SMProcessor.currentLocal().scheduler
        if (localScheduler.currentThread == null) {
            localScheduler.currentThread =
                ProcessManager.getBootstrapThread()?.also { thread ->
                    thread.state = TaskState.RUNNING
                }
        }
        scheduled.store(true)
        SMProcessor.locals.values.forEach {
            it.scheduler.scheduled.store(true)
        }
    }

    fun disableScheduler() = scheduled.store(false)

    fun enqueueThread(thread: Thread) {
        val target = selectTargetCpu()
        enqueueThreadOn(thread, target.lapicId.toUInt())
    }

    fun enqueueThreadOn(thread: Thread, targetLapicId: UInt): Boolean {
        if (thread.isQueued) {
            return false
        }

        val target = SMProcessor.locals[targetLapicId] ?: run {
            println("Scheduler: target core $targetLapicId is unavailable")
            return false
        }

        enqueueLocal(target.scheduler, thread)
        return true
    }

    private fun enqueueLocal(scheduler: PerCpuScheduler, thread: Thread) {
        if (thread.isQueued) {
            return
        }

        thread.state = TaskState.READY
        thread.isQueued = true
        scheduler.readyQueue.addLast(thread)
    }

    fun scheduler(regs: PtraceRegisters, irqNum: ULong) {
        val localScheduler = SMProcessor.currentLocal().scheduler
        if (!localScheduler.initialized ||
            !localScheduler.scheduled.load() ||
            irqNum != 1uL ||
            !scheduled.load()
        ) {
            return
        }

        val running = localScheduler.currentThread ?: run {
            val initial = dequeueNextRunnable(localScheduler) ?: return
            switchTo(localScheduler, initial)
            if (!initial.restoreTo(regs)) {
                println("Scheduler: thread ${initial.id} has no context")
            }
            return
        }

        running.saveFrom(regs)
        if (running.state == TaskState.RUNNING) {
            running.state = TaskState.READY
            enqueueLocal(localScheduler, running)
        }

        val next = dequeueNextRunnable(localScheduler) ?: run {
            switchTo(localScheduler, running)
            running.restoreTo(regs)
            return
        }

        switchTo(localScheduler, next)
        if (!next.restoreTo(regs)) {
            println("Scheduler: restore failed for thread ${next.id}, stay on ${running.id}")
            next.state = TaskState.READY
            enqueueLocal(localScheduler, next)
            localScheduler.readyQueue.remove(running)
            running.isQueued = false
            switchTo(localScheduler, running)
            running.restoreTo(regs)
        }
    }

    fun apInitialize() {
        val localScheduler = SMProcessor.currentLocal().scheduler
        initializeCurrentCpu(localScheduler.currentThread, false)
    }

    fun initialize() {
        if (!irqRegistered) {
            if (!IrqController.registerAction(1, ::scheduler)) {
                println("Scheduler: failed to register timer IRQ action")
                return
            }
            irqRegistered = true
        }

        initializeCurrentCpu(ProcessManager.getBootstrapThread(), true)
    }

    private fun initializeCurrentCpu(bootstrapThread: Thread?, is_bsp: Boolean) {
        val local = SMProcessor.currentLocal()
        val localScheduler = local.scheduler
        if (localScheduler.initialized) {
            return
        }

        localScheduler.currentThread =
            bootstrapThread?.also { thread ->
                thread.state = TaskState.RUNNING
                installThreadArchitecture(thread, null)
            }
        localScheduler.initialized = true
        localScheduler.scheduled.store(true)

        if (is_bsp)
            println(
                "Scheduler: initialized policy=RRS core=${local.lapicId} " +
                        "queue=${localScheduler.readyQueue.size}",
            )
    }

    private fun prepareApBootstrapThreads() {
        SMProcessor.locals.values
            .filterNot(CpuLocal::isBsp)
            .sortedBy(CpuLocal::lapicId)
            .forEach { local ->
                if (local.scheduler.currentThread == null) {
                    local.scheduler.currentThread = ProcessManager.getNewApIdleThread()
                }
            }
    }

    private fun selectTargetCpu(): CpuLocal {
        val cpus = SMProcessor.locals.values.sortedWith(
            compareBy<CpuLocal> { if (it.isBsp) 0 else 1 }
                .thenBy { it.lapicId },
        )
        if (cpus.isEmpty()) {
            return SMProcessor.currentLocal()
        }

        val target = cpus[nextCpuIndex % cpus.size]
        nextCpuIndex = (nextCpuIndex + 1) % cpus.size
        return target
    }

    private fun dequeueNextRunnable(scheduler: PerCpuScheduler): Thread? {
        while (scheduler.readyQueue.isNotEmpty()) {
            val thread = scheduler.readyQueue.removeFirst()
            thread.isQueued = false
            if (thread.state == TaskState.READY) {
                return thread
            }
        }
        return null
    }

    private fun switchTo(scheduler: PerCpuScheduler, thread: Thread) {
        val previous = scheduler.currentThread
        installThreadArchitecture(thread, previous)
        scheduler.currentThread = thread
        thread.state = TaskState.RUNNING
    }

    private fun installThreadArchitecture(thread: Thread, previous: Thread?) {
        val nextDirectory = thread.process.vma.pageDirectory
        if (previous == null ||
            previous.process.vma.pageDirectory.pml4PhysicalAddress !=
            nextDirectory.pml4PhysicalAddress
        ) {
            nextDirectory.activate()
        }

        if (thread.kernelStackTop != 0uL) {
            SMProcessor.setKernelStack(thread.kernelStackTop)
        }
    }
}
