@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KernelCoroutineRuntimeTest {
    @Test
    fun childFailureIsReportedWithoutCancellingRootOrSibling() {
        val clock = RuntimeFakeClock()
        val dispatcher = runtimeDispatcher(clock)
        val reported = mutableListOf<Throwable>()
        val reporter: (Throwable) -> Unit = reported::add
        val runtime = createKernelCoroutineRuntime(dispatcher, reporter)
        var siblingRan = false

        try {
            runtime.scope.launch { error("child failed") }
            runtime.scope.launch { siblingRan = true }

            while (dispatcher.hasReadyWork()) {
                dispatcher.runReadyBatch()
            }

            assertTrue(runtime.job.isActive)
            assertTrue(siblingRan)
            assertEquals(1, reported.size)
            assertEquals("child failed", reported.single().message)
        } finally {
            runtime.job.cancel()
        }
    }

    @Test
    fun dispatcherBeforeInitializationFailsWithDeliberateCheck() {
        KernelCoroutines.shutdown()

        val failure = assertFailsWith<IllegalStateException> {
            KernelCoroutines.dispatcher
        }

        assertEquals("Kernel coroutines are not initialized", failure.message)
    }

    @Test
    fun repeatedInjectedInitializationRetainsOriginalRuntime() {
        KernelCoroutines.shutdown()
        val firstDispatcher = runtimeDispatcher()

        try {
            assertTrue(KernelCoroutines.initialize(hpetReady = true) { firstDispatcher })
            val firstScope = KernelCoroutines.scope
            val firstJob = firstScope.coroutineContext[Job]

            assertTrue(KernelCoroutines.initialize(hpetReady = true) {
                error("idempotent initialization must not create a dispatcher")
            })

            assertSame(firstDispatcher, KernelCoroutines.dispatcher)
            assertSame(firstScope, KernelCoroutines.scope)
            assertTrue(firstJob?.isActive == true)
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun shutdownClearsTimeoutAndClosesOldDispatcher() {
        KernelCoroutines.shutdown()
        val clock = RuntimeFakeClock()
        val dispatcher = runtimeDispatcher(clock)
        var timeoutRan = false

        try {
            assertTrue(KernelCoroutines.initialize(hpetReady = true) { dispatcher })
            val handle = dispatcher.invokeOnTimeout(
                timeMillis = 5,
                block = Runnable { timeoutRan = true },
                context = EmptyCoroutineContext,
            )

            KernelCoroutines.shutdown()
            handle.dispose()
            handle.dispose()
            clock.nowNanos = 5_000_000uL

            assertEquals(0, dispatcher.runReadyBatch())
            assertFalse(dispatcher.hasReadyWork())
            assertFalse(timeoutRan)
            val dispatchFailure = assertFailsWith<IllegalStateException> {
                dispatcher.dispatch(EmptyCoroutineContext, Runnable {})
            }
            val scheduleFailure = assertFailsWith<IllegalStateException> {
                dispatcher.invokeOnTimeout(0, Runnable {}, EmptyCoroutineContext)
            }
            assertEquals("KernelDispatcher is shut down", dispatchFailure.message)
            assertEquals("KernelDispatcher is shut down", scheduleFailure.message)
            dispatcher.shutdown()
            dispatcher.shutdown()
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun shutdownAllowsFreshInjectedInitialization() {
        KernelCoroutines.shutdown()
        val firstDispatcher = runtimeDispatcher()
        val secondDispatcher = runtimeDispatcher()

        try {
            assertTrue(KernelCoroutines.initialize(hpetReady = true) { firstDispatcher })
            val firstScope = KernelCoroutines.scope
            val firstJob = firstScope.coroutineContext[Job]

            KernelCoroutines.shutdown()

            assertFalse(firstJob?.isActive == true)
            assertFailsWith<IllegalStateException> { KernelCoroutines.dispatcher }
            assertFailsWith<IllegalStateException> { KernelCoroutines.scope }
            assertTrue(KernelCoroutines.initialize(hpetReady = true) { secondDispatcher })
            assertSame(secondDispatcher, KernelCoroutines.dispatcher)
            assertNotSame(firstScope, KernelCoroutines.scope)
            assertTrue(KernelCoroutines.scope.coroutineContext[Job]?.isActive == true)
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun unavailableHpetGateDoesNotCreateOrPublishRuntime() {
        KernelCoroutines.shutdown()

        try {
            assertFalse(KernelCoroutines.initialize(hpetReady = false) {
                error("dispatcher factory must not run")
            })
            assertFailsWith<IllegalStateException> { KernelCoroutines.dispatcher }
            assertFailsWith<IllegalStateException> { KernelCoroutines.scope }
        } finally {
            KernelCoroutines.shutdown()
        }
    }
}

private fun runtimeDispatcher(clock: RuntimeFakeClock = RuntimeFakeClock()): KernelDispatcher = KernelDispatcher(
    nanoTime = clock::nanoTime,
    criticalSection = RuntimeNoOpCriticalSection,
    failureReporter = { throw AssertionError("Unexpected dispatcher failure", it) },
)

private class RuntimeFakeClock(var nowNanos: ULong = 0uL) {
    fun nanoTime(): ULong = nowNanos
}

private object RuntimeNoOpCriticalSection : CriticalSection {
    override fun <T> withLock(block: () -> T): T = block()
}
