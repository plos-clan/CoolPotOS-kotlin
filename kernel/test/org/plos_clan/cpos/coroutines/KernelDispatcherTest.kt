@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KernelDispatcherTest {
    @Test
    fun dispatchQueuesWorkUntilReadyBatchRuns() {
        val dispatcher = dispatcher()
        var executed = false

        dispatcher.dispatch(EmptyCoroutineContext, Runnable { executed = true })

        assertFalse(executed)
        assertTrue(dispatcher.hasReadyWork())
        assertEquals(1, dispatcher.runReadyBatch())
        assertTrue(executed)
        assertFalse(dispatcher.hasReadyWork())
    }

    @Test
    fun delayResumesAtItsNanosecondDeadline() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var started = false
        var resumed = false

        try {
            scope.launch {
                started = true
                delay(5)
                resumed = true
            }
            assertFalse(started)
            dispatcher.runReadyBatch()
            assertTrue(started)

            clock.nowNanos = 4_999_999uL
            assertEquals(0, dispatcher.runReadyBatch())
            assertFalse(resumed)

            clock.nowNanos = 5_000_000uL
            assertEquals(1, dispatcher.runReadyBatch())
            assertTrue(resumed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingSuspendedDelayPreventsPostDelayBody() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var resumed = false

        try {
            val job = scope.launch {
                delay(5)
                resumed = true
            }
            dispatcher.runReadyBatch()

            job.cancel()
            drainReady(dispatcher)
            clock.nowNanos = 5_000_000uL
            dispatcher.runReadyBatch()

            assertFalse(resumed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingRunnableIsReportedAndDoesNotStopBatch() {
        val failure = IllegalStateException("boom")
        val reported = mutableListOf<Throwable>()
        val dispatcher = KernelDispatcher(
            nanoTime = { 0uL },
            criticalSection = NoOpCriticalSection,
            failureReporter = reported::add,
        )
        var secondRan = false
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { throw failure })
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { secondRan = true })

        assertEquals(2, dispatcher.runReadyBatch())

        assertEquals(1, reported.size)
        assertSame(failure, reported.single())
        assertTrue(secondRan)
    }

    @Test
    fun throwingReporterDoesNotStopClaimedBatch() {
        val failure = IllegalStateException("task failure")
        var reported: Throwable? = null
        val dispatcher = KernelDispatcher(
            nanoTime = { 0uL },
            criticalSection = NoOpCriticalSection,
            failureReporter = {
                reported = it
                throw IllegalStateException("reporter failure")
            },
        )
        var secondRan = false
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { throw failure })
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { secondRan = true })

        assertEquals(2, dispatcher.runReadyBatch())

        assertSame(failure, reported)
        assertTrue(secondRan)
    }

    @Test
    fun shutdownFromCallbackStopsRemainingClaimedBatch() {
        val dispatcher = dispatcher()
        val executed = mutableListOf<String>()
        dispatcher.invokeOnTimeout(0, Runnable {
            executed += "first"
            dispatcher.shutdown()
        }, EmptyCoroutineContext)
        dispatcher.invokeOnTimeout(0, Runnable {
            executed += "second"
        }, EmptyCoroutineContext)

        val executionCount = dispatcher.runReadyBatch()

        assertEquals(1, executionCount)
        assertEquals(listOf("first"), executed)
    }

    @Test
    fun asyncAwaitCompletesThroughDispatcher() {
        val dispatcher = dispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var result: Int? = null

        try {
            scope.launch {
                result = scope.async { 42 }.await()
            }

            drainReady(dispatcher)

            assertEquals(42, result)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun timeoutUsesFakeClockDeadline() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var result: Unit? = Unit
        var completed = false

        try {
            scope.launch {
                result = withTimeoutOrNull(5) {
                    delay(20)
                }
                completed = true
            }
            dispatcher.runReadyBatch()
            assertFalse(completed)

            clock.nowNanos = 4_999_999uL
            dispatcher.runReadyBatch()
            assertFalse(completed)

            clock.nowNanos = 5_000_000uL
            drainReady(dispatcher)

            assertTrue(completed)
            assertNull(result)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun callbacksAndReporterRunOutsideCriticalSection() {
        val criticalSection = TrackingCriticalSection()
        var callbackRan = false
        var reporterRan = false
        val dispatcher = KernelDispatcher(
            nanoTime = { 0uL },
            criticalSection = criticalSection,
            failureReporter = {
                assertFalse(criticalSection.held)
                reporterRan = true
            },
        )
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            assertFalse(criticalSection.held)
            callbackRan = true
            throw IllegalStateException("reported outside lock")
        })

        dispatcher.runReadyBatch()

        assertTrue(callbackRan)
        assertTrue(reporterRan)
    }

    @Test
    fun disposedTimeoutDoesNotRun() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        var executed = false
        val handle = dispatcher.invokeOnTimeout(5, Runnable { executed = true }, EmptyCoroutineContext)

        handle.dispose()
        handle.dispose()
        clock.nowNanos = 5_000_000uL
        assertEquals(0, dispatcher.runReadyBatch())

        assertFalse(executed)
    }

    @Test
    fun toStringIdentifiesBspDispatcher() {
        assertEquals("KernelDispatcher[BSP]", dispatcher().toString())
    }

    private fun dispatcher(clock: FakeClock = FakeClock()): KernelDispatcher = KernelDispatcher(
        nanoTime = clock::nanoTime,
        criticalSection = NoOpCriticalSection,
        failureReporter = { throw AssertionError("Unexpected dispatcher failure", it) },
    )

    private fun drainReady(dispatcher: KernelDispatcher) {
        repeat(8) {
            dispatcher.runReadyBatch()
            if (!dispatcher.hasReadyWork()) {
                return
            }
        }
        throw AssertionError("Dispatcher did not become idle")
    }
}

private class FakeClock(var nowNanos: ULong = 0uL) {
    fun nanoTime(): ULong = nowNanos
}

private object NoOpCriticalSection : CriticalSection {
    override fun <T> withLock(block: () -> T): T = block()
}

private class TrackingCriticalSection : CriticalSection {
    var held = false
        private set

    override fun <T> withLock(block: () -> T): T {
        check(!held)
        held = true
        return try {
            block()
        } finally {
            held = false
        }
    }
}
