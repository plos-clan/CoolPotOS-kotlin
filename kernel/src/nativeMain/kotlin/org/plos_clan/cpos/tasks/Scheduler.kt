@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class PerCpuScheduler {
    var bootstrapThread: Thread? = null
    val scheduled = AtomicBoolean(false)
    val signalPasses = AtomicInt(0)

    fun waitUntilEnabled() {
        while (!scheduled.load()) {
            bridge.asm_pause()
        }
    }
}

object Scheduler {
    private val nextCpuIndex = AtomicInt(0)
    private val schedulingCpus by lazy(LazyThreadSafetyMode.NONE) {
        SMProcessor.locals.values.sortedWith(
            compareBy<CpuLocal> { if (it.isBsp) 0 else 1 }
                .thenBy(CpuLocal::lapicId),
        )
    }

    fun enableScheduler() {
        prepareApBootstrapThreads()
        bridge.fast_handoff_set_enabled(1u.toUByte())
        SMProcessor.locals.values.forEach { local ->
            local.scheduler.scheduled.store(true)
        }
    }

    fun enqueueThread(thread: Thread) {
        val target = selectTargetCpu()
        enqueueThreadOn(thread, target.lapicId.toUInt())
    }

    fun enqueueThreadOn(thread: Thread, targetLapicId: UInt): Boolean {
        if (SMProcessor.locals[targetLapicId] == null) {
            println("Scheduler: target core $targetLapicId is unavailable")
            return false
        }
        val accepted = bridge.fast_handoff_enqueue(
            thread.nativeContext,
            targetLapicId.toULong(),
        )
        if (accepted) {
            thread.bindToCpu(targetLapicId)
            SignalRouter.requestDelivery(thread)
        }
        return accepted
    }

    fun parkCurrent(): Boolean = bridge.fast_handoff_park_current()

    fun yieldCurrent(): Boolean = bridge.fast_handoff_yield()

    fun wake(thread: Thread): Boolean {
        if (thread.process.signals.deferWake(thread.process, thread)) return true
        return bridge.fast_handoff_unpark(thread.nativeContext)
    }

    fun apInitialize(): Boolean {
        val thread = SMProcessor.currentLocal().scheduler.bootstrapThread
        return initializeCurrentCpu(thread, false) && finishBootstrap()
    }

    fun initialize(): Boolean =
        initializeCurrentCpu(ProcessManager.getBootstrapThread(), true)

    fun finishBootstrap(): Boolean {
        val local = SMProcessor.currentLocal()
        val thread = local.scheduler.bootstrapThread ?: return false
        return bridge.fast_handoff_finish_bootstrap(thread.nativeContext)
    }

    private fun initializeCurrentCpu(bootstrapThread: Thread?, isBsp: Boolean): Boolean {
        val local = SMProcessor.currentLocal()
        val localScheduler = local.scheduler
        val thread = bootstrapThread ?: run {
            println("Scheduler: core ${local.lapicId} has no bootstrap thread")
            return false
        }
        if (!bridge.fast_handoff_bind_current(
                thread.nativeContext,
                local.lapicId.toULong(),
                if (isBsp) 1u.toUByte() else 0u.toUByte(),
            )
        ) {
            println("Scheduler: cannot bind bootstrap thread on core ${local.lapicId}")
            return false
        }
        thread.bindToCpu(local.lapicId.toUInt())

        localScheduler.bootstrapThread = thread

        if (isBsp) {
            println("Scheduler: initialized policy=RRS-fast-handoff core=${local.lapicId}")
        }
        return true
    }

    private fun prepareApBootstrapThreads() {
        SMProcessor.locals.values
            .filterNot(CpuLocal::isBsp)
            .sortedBy(CpuLocal::lapicId)
            .forEach { local ->
                if (local.scheduler.bootstrapThread == null) {
                    local.scheduler.bootstrapThread = ProcessManager.getNewApIdleThread()
                }
            }
    }

    private fun selectTargetCpu(): CpuLocal {
        if (schedulingCpus.isEmpty()) {
            return SMProcessor.currentLocal()
        }

        val index = nextCpuIndex.fetchAndAdd(1).toUInt() % schedulingCpus.size.toUInt()
        return schedulingCpus[index.toInt()]
    }
}
