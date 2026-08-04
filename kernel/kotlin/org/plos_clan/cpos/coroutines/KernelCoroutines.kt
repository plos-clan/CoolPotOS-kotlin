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
    private lateinit var runtime: KernelCoroutineRuntime

    lateinit var dispatcher: KernelDispatcher
        private set

    val scope: CoroutineScope
        get() {
            check(initialized)
            return runtime.scope
        }

    fun initialize(): Boolean {
        if (initialized) {
            return true
        }
        if (!Hpet.isReady) {
            println("Kernel coroutines: HPET is unavailable")
            return false
        }

        dispatcher = KernelDispatcher(failureReporter = ::reportFailure)
        runtime = createKernelCoroutineRuntime(dispatcher, ::reportFailure)
        initialized = true
        println("Kernel coroutines initialized dispatcher=$dispatcher")
        return true
    }

    fun launchSmokeTest() {
        scope.launch {
            delay(10)
            println("Coroutine smoke test passed")
        }
    }

    fun runEventLoop(): Nothing {
        check(initialized)
        while (true) {
            dispatcher.runReadyBatch()
            if (dispatcher.hasReadyWork()) {
                continue
            }
            bridge.wait_for_interrupt()
        }
    }

    internal fun shutdown() {
        if (!initialized) {
            return
        }

        runtime.job.cancel()
        while (dispatcher.hasReadyWork()) {
            dispatcher.runReadyBatch()
        }
        initialized = false
    }

    private fun reportFailure(throwable: Throwable) {
        println("Uncaught kernel coroutine failure: $throwable")
        throwable.printStackTrace()
    }
}
