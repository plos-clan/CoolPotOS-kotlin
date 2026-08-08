@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.acpi.aml.Aml
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic

private const val AML_EVENT_BATCH_SIZE = 64
private const val NOT_INITIALIZED_MESSAGE = "Kernel coroutines are not initialized"

object KernelCoroutines {
    private var activeDispatcher: KernelDispatcher? = null
    private var activeScope: CoroutineScope? = null

    val dispatcher: KernelDispatcher
        get() = checkNotNull(activeDispatcher) { NOT_INITIALIZED_MESSAGE }

    val scope: CoroutineScope
        get() = checkNotNull(activeScope) { NOT_INITIALIZED_MESSAGE }

    fun initialize(): Boolean {
        if (activeScope != null) {
            return true
        }
        if (!TscClock.isReady) {
            println("Kernel coroutines: TSC clock is unavailable")
            return false
        }
        if (!LocalApic.isBspDeadlineTimerReady) {
            println("Kernel coroutines: BSP LAPIC TSC-deadline timer is unavailable")
            return false
        }

        val dispatcher = KernelDispatcher(::reportFailure)
        val exceptionHandler = CoroutineExceptionHandler { _, failure ->
            reportFailure(failure)
        }
        activeDispatcher = dispatcher
        activeScope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineName("kernel") + exceptionHandler,
        )
        println("Kernel coroutines initialized dispatcher=$dispatcher")
        return true
    }

    internal fun launchAmlEventWorker() {
        val wakeup = dispatcher.createEvent()
        Aml.installEventWakeup(wakeup)
        while (Aml.processPendingEvents(AML_EVENT_BATCH_SIZE) != 0) {
        }
        scope.launch(CoroutineName("aml-events")) {
            while (isActive) {
                while (Aml.processPendingEvents(AML_EVENT_BATCH_SIZE) != 0) {
                    yield()
                }
                wakeup.await()
            }
        }
    }

    fun runEventLoop(): Nothing {
        val dispatcher = dispatcher
        while (true) {
            dispatcher.runReadyBatch()
            val wakeSequence = bridge.fast_handoff_wake_sequence()
            if (dispatcher.hasReadyWork()) {
                continue
            }
            bridge.fast_handoff_park_kotlin(
                dispatcher.nextDeadlineNanos() ?: 0uL,
                wakeSequence,
            )
        }
    }

    private fun reportFailure(failure: Throwable) {
        println("Uncaught kernel coroutine failure: $failure")
    }
}
