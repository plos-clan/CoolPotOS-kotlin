@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.drivers.acpi.apic.LocalApic

internal const val COROUTINE_SMOKE_SUCCESS_MARKER = "Coroutine smoke test passed"

internal data class KernelCoroutineRuntime(
    val job: CompletableJob,
    val scope: CoroutineScope,
)

internal fun createKernelCoroutineRuntime(
    dispatcher: KernelDispatcher,
    reportFailure: (Throwable) -> Unit,
): KernelCoroutineRuntime {
    val job = SupervisorJob()
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        reportFailure(throwable)
    }
    val scope = CoroutineScope(job + dispatcher + CoroutineName("kernel") + exceptionHandler)
    return KernelCoroutineRuntime(job, scope)
}

object KernelCoroutines {
    private var initialized = false
    private var runtime: KernelCoroutineRuntime? = null
    private var activeDispatcher: KernelDispatcher? = null

    val dispatcher: KernelDispatcher
        get() = checkNotNull(activeDispatcher) { NOT_INITIALIZED_MESSAGE }

    val scope: CoroutineScope
        get() {
            check(initialized) { NOT_INITIALIZED_MESSAGE }
            return checkNotNull(runtime).scope
        }

    fun initialize(): Boolean = initialize(
        hpetReady = Hpet.isReady,
        bspPeriodicTimerReady = LocalApic.isBspPeriodicTimerReady,
        dispatcherFactory = { KernelDispatcher(failureReporter = ::reportFailure) },
    )

    internal fun initialize(
        hpetReady: Boolean,
        bspPeriodicTimerReady: Boolean,
        dispatcherFactory: () -> KernelDispatcher,
    ): Boolean {
        if (initialized) {
            return true
        }
        if (!hpetReady) {
            println("Kernel coroutines: HPET is unavailable")
            return false
        }
        if (!bspPeriodicTimerReady) {
            println("Kernel coroutines: BSP LAPIC periodic timer is unavailable")
            return false
        }

        val dispatcher = dispatcherFactory()
        val newRuntime = createKernelCoroutineRuntime(dispatcher, ::reportFailure)
        runtime = newRuntime
        activeDispatcher = dispatcher
        initialized = true
        println("Kernel coroutines initialized dispatcher=$dispatcher")
        return true
    }

    fun launchSmokeTest() {
        scope.launch {
            delay(10)
            println(COROUTINE_SMOKE_SUCCESS_MARKER)
        }
    }

    fun runEventLoop(): Nothing {
        check(initialized) { NOT_INITIALIZED_MESSAGE }
        val eventDispatcher = dispatcher
        while (true) {
            eventDispatcher.runReadyBatch()
            if (eventDispatcher.hasReadyWork()) {
                continue
            }
            bridge.wait_for_interrupt()
        }
    }

    internal fun shutdown() {
        if (!initialized) {
            return
        }

        val currentRuntime = checkNotNull(runtime)
        val currentDispatcher = checkNotNull(activeDispatcher)
        currentRuntime.job.cancel()
        while (currentDispatcher.hasReadyWork()) {
            currentDispatcher.runReadyBatch()
        }
        currentDispatcher.shutdown()
        runtime = null
        activeDispatcher = null
        initialized = false
    }

    private fun reportFailure(throwable: Throwable) {
        println("Uncaught kernel coroutine failure: $throwable")
        throwable.printStackTrace()
    }

    private const val NOT_INITIALIZED_MESSAGE = "Kernel coroutines are not initialized"
}
