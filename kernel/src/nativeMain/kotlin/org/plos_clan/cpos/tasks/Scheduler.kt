@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, ExperimentalUnsignedTypes::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic
import org.plos_clan.cpos.tasks.cgroup.Cgroups
import org.plos_clan.cpos.utils.restoreInterrupts
import org.plos_clan.cpos.utils.saveInterrupts
import platform.posix.memset
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
    internal lateinit var queue: CPointer<bridge.fast_cpu_t>
        private set

    internal fun initialize(local: CpuLocal, levels: Int, slices: CPointer<ULongVar>) {
        val header = sizeOf<bridge.fast_cpu_t>()
        val size = header + levels * sizeOf<ULongVar>()
        val allocation = nativeHeap.alloc(size, alignOf<bridge.fast_cpu_t>())
        val storage = allocation.reinterpret<ByteVar>().ptr
        memset(storage, 0, size.toULong())
        queue = storage.reinterpret()
        val counts = checkNotNull(storage + header)
        with(queue.pointed) {
            this.counts = counts.reinterpret()
            this.slices = slices
            this.levels = levels.toUInt()
            strongest = this.levels - 1u
            logical_id = local.cpuid.toUInt()
            lapic_id = local.lapicId.toULong()
            is_bsp = if (local.isBsp) 1u else 0u
        }
    }

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

    private val slices by lazy(LazyThreadSafetyMode.NONE) { policy.sliceTable().pin() }

    internal fun service(local: CpuLocal): ULong {
        val sequence = bridge.fast_handoff_service()
        val queue = local.scheduler.queue
        if (queue.pointed.device_pending != 0.toUByte()) {
            val flags = saveInterrupts()
            try {
                queue.pointed.device_pending = 0u
                if (LocalApic.serviceDevices()) queue.pointed.device_pending = 1u
            } finally {
                restoreInterrupts(flags)
            }
        }
        if (!bridge.fast_handoff_needs_balance()) return sequence
        policy.distribute(local, { bridge.fast_handoff_load(it.scheduler.queue) }) { target ->
            bridge.fast_handoff_transfer(queue, target.scheduler.queue)
        }
        return sequence
    }

    fun initialize(): Boolean {
        val budgets = slices.addressOf(0)
        for (local in policy.processors) local.scheduler.initialize(local, policy.levels, budgets)
        if (!initializeCurrentCpu(ProcessManager.getBootstrapThread())) return false
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
            load = { bridge.fast_handoff_load(it.scheduler.queue) },
        )
        enqueueThreadOn(thread, target.lapicId.toUInt())
    }

    fun enqueueThreadOn(thread: Thread, targetLapicId: UInt): Boolean {
        val local = SMProcessor.locals[targetLapicId]
        if (local == null) {
            println("Scheduler: target core $targetLapicId is unavailable")
            return false
        }
        val accepted = thread.nativeTask.access {
            bridge.fast_handoff_enqueue(it, local.scheduler.queue)
        }
        if (accepted) SignalRouter.requestDelivery(thread)
        return accepted
    }

    fun preparePark(): ULong = bridge.fast_handoff_prepare_park()

    fun parkCurrent(sequence: ULong): Boolean {
        if (Cgroups.awaitThaw(ProcessManager.currentThread())) return true
        return bridge.fast_handoff_park_current(0uL, sequence)
    }

    fun parkCurrentUntil(deadlineNanos: ULong, sequence: ULong): Boolean {
        if (Cgroups.awaitThaw(ProcessManager.currentThread())) return true
        return deadlineNanos != 0uL && bridge.fast_handoff_park_current(deadlineNanos, sequence)
    }

    fun yieldCurrent(): Boolean = bridge.fast_handoff_yield()

    fun wake(thread: Thread): Boolean {
        if (thread.process.signals.deferWake(thread.process, thread)) return true
        return thread.nativeTask.access { bridge.fast_handoff_unpark(it) }
    }

    fun apInitialize(): Boolean {
        val thread = SMProcessor.currentLocal().scheduler.bootstrapThread
        return initializeCurrentCpu(thread) && finishBootstrap()
    }

    fun finishBootstrap(): Boolean = bridge.fast_handoff_finish_bootstrap()

    private fun initializeCurrentCpu(bootstrapThread: Thread?): Boolean {
        val local = SMProcessor.currentLocal()
        val localScheduler = local.scheduler
        val thread = bootstrapThread ?: run {
            println("Scheduler: core ${local.lapicId} has no bootstrap thread")
            return false
        }
        val bound = thread.nativeTask.access { bridge.fast_handoff_bind_current(it, localScheduler.queue) }
        if (!bound) {
            println("Scheduler: cannot bind bootstrap thread on core ${local.lapicId}")
            return false
        }
        localScheduler.bootstrapThread = thread

        if (local.isBsp) {
            println("Scheduler: initialized policy=weighted-fair core=${local.lapicId}")
        }
        return true
    }
}
