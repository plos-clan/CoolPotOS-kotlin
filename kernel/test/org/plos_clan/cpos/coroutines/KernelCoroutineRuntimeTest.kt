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
            assertTrue(KernelCoroutines.initialize(hpetReady = true, bspPeriodicTimerReady = true) { firstDispatcher })
            val firstScope = KernelCoroutines.scope
            val firstJob = firstScope.coroutineContext[Job]

            assertTrue(KernelCoroutines.initialize(hpetReady = true, bspPeriodicTimerReady = true) {
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
            assertTrue(KernelCoroutines.initialize(hpetReady = true, bspPeriodicTimerReady = true) { dispatcher })
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
            assertTrue(KernelCoroutines.initialize(hpetReady = true, bspPeriodicTimerReady = true) { firstDispatcher })
            val firstScope = KernelCoroutines.scope
            val firstJob = firstScope.coroutineContext[Job]

            KernelCoroutines.shutdown()

            assertFalse(firstJob?.isActive == true)
            assertFailsWith<IllegalStateException> { KernelCoroutines.dispatcher }
            assertFailsWith<IllegalStateException> { KernelCoroutines.scope }
            assertTrue(KernelCoroutines.initialize(hpetReady = true, bspPeriodicTimerReady = true) { secondDispatcher })
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
            assertFalse(KernelCoroutines.initialize(hpetReady = false, bspPeriodicTimerReady = true) {
                error("dispatcher factory must not run")
            })
            assertFailsWith<IllegalStateException> { KernelCoroutines.dispatcher }
            assertFailsWith<IllegalStateException> { KernelCoroutines.scope }
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun unavailableBspPeriodicTimerGateDoesNotCreateOrPublishRuntime() {
        KernelCoroutines.shutdown()

        try {
            assertFalse(
                KernelCoroutines.initialize(
                    hpetReady = true,
                    bspPeriodicTimerReady = false,
                ) {
                    error("dispatcher factory must not run")
                },
            )
            assertFailsWith<IllegalStateException> { KernelCoroutines.dispatcher }
            assertFailsWith<IllegalStateException> { KernelCoroutines.scope }
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun amlEventWorkerUsesBoundedBatchesAndSleepsWhenIdle() {
        KernelCoroutines.shutdown()
        val clock = RuntimeFakeClock()
        val dispatcher = runtimeDispatcher(clock)
        val requestedBatchSizes = mutableListOf<Int>()
        val results = ArrayDeque(listOf(AML_EVENT_BATCH_SIZE, 1, 0, 0))

        try {
            assertTrue(
                KernelCoroutines.initialize(
                    hpetReady = true,
                    bspPeriodicTimerReady = true,
                ) { dispatcher },
            )
            val worker = KernelCoroutines.launchAmlEventWorker { maxEvents ->
                requestedBatchSizes += maxEvents
                results.removeFirstOrNull() ?: 0
            }

            assertTrue(worker.isActive)
            assertEquals(1, dispatcher.runReadyBatch())
            assertEquals(listOf(AML_EVENT_BATCH_SIZE), requestedBatchSizes)

            assertEquals(1, dispatcher.runReadyBatch())
            assertEquals(listOf(AML_EVENT_BATCH_SIZE, AML_EVENT_BATCH_SIZE), requestedBatchSizes)

            assertEquals(1, dispatcher.runReadyBatch())
            assertEquals(3, requestedBatchSizes.size)
            assertEquals(0, dispatcher.runReadyBatch())

            clock.nowNanos = AML_EVENT_IDLE_POLL_MILLIS.toULong() * 1_000_000uL - 1uL
            assertEquals(0, dispatcher.runReadyBatch())
            assertEquals(3, requestedBatchSizes.size)

            clock.nowNanos++
            assertEquals(1, dispatcher.runReadyBatch())
            assertEquals(4, requestedBatchSizes.size)
            assertTrue(requestedBatchSizes.all { it == AML_EVENT_BATCH_SIZE })
        } finally {
            KernelCoroutines.shutdown()
        }
    }

    @Test
    fun amlEventWorkerIsSingletonAndStopsWithRootScope() {
        KernelCoroutines.shutdown()
        val clock = RuntimeFakeClock()
        val dispatcher = runtimeDispatcher(clock)
        var calls = 0

        try {
            assertTrue(
                KernelCoroutines.initialize(
                    hpetReady = true,
                    bspPeriodicTimerReady = true,
                ) { dispatcher },
            )
            val firstWorker = KernelCoroutines.launchAmlEventWorker {
                calls++
                0
            }
            val sameWorker = KernelCoroutines.launchAmlEventWorker {
                error("an active AML worker must be reused")
            }

            assertSame(firstWorker, sameWorker)
            assertEquals(1, dispatcher.runReadyBatch())
            assertEquals(1, calls)

            KernelCoroutines.shutdown()
            assertFalse(firstWorker.isActive)
            clock.nowNanos = AML_EVENT_IDLE_POLL_MILLIS.toULong() * 1_000_000uL
            assertEquals(0, dispatcher.runReadyBatch())
            assertEquals(1, calls)
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
