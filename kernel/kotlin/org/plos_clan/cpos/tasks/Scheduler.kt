@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class PerCpuScheduler {
    var bootstrapThread: Thread? = null
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
        scheduled.store(true)
        SMProcessor.locals.values.forEach { local ->
            local.scheduler.scheduled.store(true)
        }
    }

    fun disableScheduler() {
        bridge.fast_handoff_set_enabled(0u.toUByte())
        scheduled.store(false)
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
        return bridge.fast_handoff_enqueue(
            thread.nativeContext,
            targetLapicId.toULong(),
        )
    }

    fun apInitialize() {
        initializeCurrentCpu(
            SMProcessor.currentLocal().scheduler.bootstrapThread,
            false,
        )
    }

    fun initialize() {
        initializeCurrentCpu(ProcessManager.getBootstrapThread(), true)
    }

    private fun initializeCurrentCpu(bootstrapThread: Thread?, isBsp: Boolean) {
        val local = SMProcessor.currentLocal()
        val localScheduler = local.scheduler
        if (localScheduler.initialized) {
            return
        }

        val thread = bootstrapThread ?: run {
            println("Scheduler: core ${local.lapicId} has no bootstrap thread")
            return
        }
        if (!bridge.fast_handoff_bind_current(
                thread.nativeContext,
                local.lapicId.toULong(),
                if (isBsp) 1u.toUByte() else 0u.toUByte(),
            )
        ) {
            println("Scheduler: cannot bind bootstrap thread on core ${local.lapicId}")
            return
        }

        localScheduler.bootstrapThread = thread
        localScheduler.initialized = true
        localScheduler.scheduled.store(true)

        if (isBsp) {
            println(
                "Scheduler: initialized policy=RRS-fast-handoff " +
                    "core=${local.lapicId} queue=${bridge.fast_handoff_queue_size(local.lapicId.toULong())}",
            )
        }
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
