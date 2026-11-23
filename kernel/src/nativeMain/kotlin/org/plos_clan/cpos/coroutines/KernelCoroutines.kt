@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.acpi.aml.Aml
import org.plos_clan.cpos.tasks.CpuLocal
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.TaskReaper
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ThreadLocal

private const val AML_EVENT_BATCH_SIZE = 64

@ThreadLocal
private var currentWorker: KernelWorker? = null

data class CoroutineEntry(val id: Int, val name: String, val job: Job)

object KernelCoroutines {
    private val nextId = AtomicInt(0)
    val dispatcher = KernelDispatcher(TscClock, ::nextWorker, { currentWorker }, ::IrqSpinLock)
    private val handler = CoroutineExceptionHandler { _, failure -> reportFailure(failure) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("kernel") + handler)

    private fun nextWorker(): KernelWorker {
        val local = Scheduler.policy.nextProcessor { it.scheduler.worker.load.toULong() }
        return local.scheduler.worker
    }

    private val jobLock = IrqSpinLock()
    private val jobs = mutableListOf<CoroutineEntry>()

    fun snapshotJobs(): List<CoroutineEntry> = jobLock.withLock {
        jobs.toList()
    }

    fun launch(
        name: String,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = scope.launch(CoroutineName(name), start, block)
        val entry = CoroutineEntry(nextId.fetchAndAdd(1), name, job)
        jobLock.withLock { jobs += entry }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> await(block: suspend () -> T): T {
        val thread = checkNotNull(ProcessManager.currentThread())
        val local = SMProcessor.currentLocal().takeIf { it.scheduler.bootstrapThread === thread }
        val work = scope.async(CoroutineName("kernel-io")) { block() }
        work.invokeOnCompletion {
            if (local == null) Scheduler.wake(thread) else local.scheduler.worker.wake()
        }
        if (local != null) {
            runUntil(local) { work.isCompleted }
            return work.getCompleted()
        }
        while (true) {
            val sequence = Scheduler.preparePark()
            if (work.isCompleted) return work.getCompleted()
            if (!Scheduler.parkCurrent(sequence)) Scheduler.yieldCurrent()
        }
    }

    internal fun launchAmlEventWorker() {
        val wakeup = dispatcher.createEvent()
        Aml.installEventWakeup(wakeup)
        while (Aml.processPendingEvents(AML_EVENT_BATCH_SIZE) != 0) {
            bridge.asm_pause()
        }
        launch("aml-events") {
            while (isActive) {
                while (Aml.processPendingEvents(AML_EVENT_BATCH_SIZE) != 0) {
                    yield()
                }
                wakeup.await()
            }
        }
    }

    fun runEventLoop(): Nothing {
        runUntil(SMProcessor.currentLocal()) { false }
        error("Kernel event loop stopped")
    }

    private inline fun runUntil(local: CpuLocal, completed: () -> Boolean) {
        val worker = local.scheduler.worker
        currentWorker = worker
        while (!completed()) {
            val wakeSequence = Scheduler.service(local)
            val pendingSwitch = local.isBsp && TaskReaper.reapRuntime()
            val busy = worker.runReadyBatch({ steal(local) }, ::reportFailure)
            if (completed()) return
            val deadline = worker.nextDeadline() ?: 0uL
            bridge.fast_handoff_park_kotlin(deadline, wakeSequence, !busy && !pendingSwitch)
        }
    }

    private fun steal(local: CpuLocal): Runnable? {
        for (peer in Scheduler.policy.processors) {
            if (peer === local) continue
            val block = peer.scheduler.worker.steal() ?: continue
            return block
        }
        return null
    }

    private fun reportFailure(failure: Throwable) {
        println("Uncaught kernel coroutine failure: $failure")
    }
}
