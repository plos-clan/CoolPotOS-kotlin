@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.tasks.cgroup.Cgroups
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private enum class SchedulerStartupState {
    WAITING,
    BIND_REQUESTED,
    BOUND,
    ENABLED,
    FAILED,
}

class PerCpuScheduler {
    var bootstrapThread: Thread? = null
    private val startupState = AtomicReference(SchedulerStartupState.WAITING)

    internal fun requestBind() {
        check(startupState.compareAndSet(
            expectedValue = SchedulerStartupState.WAITING,
            newValue = SchedulerStartupState.BIND_REQUESTED,
        ))
    }

    internal fun waitForBindRequest() {
        check(awaitState(SchedulerStartupState.BIND_REQUESTED))
    }

    internal fun completeBind(success: Boolean) {
        check(startupState.compareAndSet(
            expectedValue = SchedulerStartupState.BIND_REQUESTED,
            newValue = if (success) SchedulerStartupState.BOUND else SchedulerStartupState.FAILED,
        ))
    }

    internal fun waitUntilBound(): Boolean = awaitState(SchedulerStartupState.BOUND)

    internal fun enable() {
        check(startupState.compareAndSet(
            expectedValue = SchedulerStartupState.BOUND,
            newValue = SchedulerStartupState.ENABLED,
        ))
    }

    internal fun waitUntilEnabled(): Boolean = awaitState(SchedulerStartupState.ENABLED)

    private fun awaitState(expected: SchedulerStartupState): Boolean {
        while (true) {
            when (startupState.load()) {
                expected -> return true
                SchedulerStartupState.FAILED -> return false
                else -> bridge.asm_pause()
            }
        }
    }
}

object Scheduler {
    internal val policy by lazy(LazyThreadSafetyMode.NONE) {
        FairSchedulingPolicy(
            SMProcessor.locals.values.sortedWith(
                compareBy<CpuLocal> { if (it.isBsp) 0 else 1 }.thenBy(CpuLocal::lapicId),
            ),
            bridge.runtime_clock_frequency(),
            frequencyHz = 1_000u,
        )
    }

    fun initialize(): Boolean {
        if (!initializeCurrentCpu(ProcessManager.getBootstrapThread(), true)) return false
        bridge.fast_handoff_yield()

        val applicationProcessors = policy.processors.filterNot(CpuLocal::isBsp)
        applicationProcessors.forEach { local ->
            if (local.scheduler.bootstrapThread == null) {
                local.scheduler.bootstrapThread = ProcessManager.getNewApIdleThread()
            }
            local.scheduler.requestBind()
        }
        if (applicationProcessors.any { !it.scheduler.waitUntilBound() }) return false

        applicationProcessors.forEach { it.scheduler.enable() }
        return true
    }

    fun enqueueThread(thread: Thread) {
        val affinity = checkNotNull(thread.nativeTask.affinity())
        val target = policy.nextProcessor(
            eligible = { CpuAffinity.contains(affinity, it.cpuid.toInt()) },
            load = { bridge.fast_handoff_queue_size(it.lapicId.toULong()) },
        )
        enqueueThreadOn(thread, target.lapicId.toUInt())
    }

    fun enqueueThreadOn(thread: Thread, targetLapicId: UInt): Boolean {
        if (SMProcessor.locals[targetLapicId] == null) {
            println("Scheduler: target core $targetLapicId is unavailable")
            return false
        }
        val accepted = thread.nativeTask.access {
            bridge.fast_handoff_enqueue(it, targetLapicId.toULong())
        }
        if (accepted) SignalRouter.requestDelivery(thread)
        return accepted
    }

    fun parkCurrent(): Boolean {
        if (Cgroups.awaitThaw(ProcessManager.currentThread())) return true
        return bridge.fast_handoff_park_current(0uL)
    }

    fun parkCurrentUntil(deadlineNanos: ULong): Boolean {
        if (Cgroups.awaitThaw(ProcessManager.currentThread())) return true
        return deadlineNanos != 0uL && bridge.fast_handoff_park_current(deadlineNanos)
    }

    fun yieldCurrent(): Boolean = bridge.fast_handoff_yield()

    fun wake(thread: Thread): Boolean {
        if (thread.process.signals.deferWake(thread.process, thread)) return true
        return thread.nativeTask.access { bridge.fast_handoff_unpark(it) }
    }

    fun apInitialize(): Boolean {
        val thread = SMProcessor.currentLocal().scheduler.bootstrapThread
        return initializeCurrentCpu(thread, false) && finishBootstrap()
    }

    fun finishBootstrap(): Boolean {
        val local = SMProcessor.currentLocal()
        val thread = local.scheduler.bootstrapThread ?: return false
        return thread.nativeTask.access { bridge.fast_handoff_finish_bootstrap(it) }
    }

    private fun initializeCurrentCpu(bootstrapThread: Thread?, isBsp: Boolean): Boolean {
        val local = SMProcessor.currentLocal()
        val localScheduler = local.scheduler
        val thread = bootstrapThread ?: run {
            println("Scheduler: core ${local.lapicId} has no bootstrap thread")
            return false
        }
        val lapicId = local.lapicId.toULong()
        val bootstrap = if (isBsp) 1u.toUByte() else 0u.toUByte()
        val cpuId = local.cpuid.toUInt()
        val bound = thread.nativeTask.access {
            bridge.fast_handoff_bind_current(it, lapicId, bootstrap, cpuId)
        }
        if (!bound) {
            println("Scheduler: cannot bind bootstrap thread on core ${local.lapicId}")
            return false
        }
        localScheduler.bootstrapThread = thread

        if (isBsp) {
            println("Scheduler: initialized policy=weighted-fair core=${local.lapicId}")
        }
        return true
    }
}
