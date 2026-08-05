@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.drivers.acpi.aml.Aml
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic

private const val AML_EVENT_BATCH_SIZE = 64
private const val AML_EVENT_IDLE_POLL_MILLIS = 1L
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
        if (!Hpet.isReady) {
            println("Kernel coroutines: HPET is unavailable")
            return false
        }
        if (!LocalApic.isBspPeriodicTimerReady) {
            println("Kernel coroutines: BSP LAPIC periodic timer is unavailable")
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
        scope.launch(CoroutineName("aml-events")) {
            while (isActive) {
                if (Aml.processPendingEvents(AML_EVENT_BATCH_SIZE) == 0) {
                    delay(AML_EVENT_IDLE_POLL_MILLIS)
                } else {
                    yield()
                }
            }
        }
    }

    fun runEventLoop(): Nothing {
        val dispatcher = dispatcher
        while (true) {
            dispatcher.runReadyBatch()
            if (dispatcher.hasReadyWork()) {
                continue
            }
            bridge.wait_for_interrupt()
        }
    }

    private fun reportFailure(failure: Throwable) {
        println("Uncaught kernel coroutine failure: $failure")
        failure.printStackTrace()
    }
}
