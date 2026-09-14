package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelDispatcherTest {
    @Test
    fun resumesOnlyAfterTheInjectedDeadline() {
        val harness = Harness()
        var resumed = false
        harness.scope.launch { delay(10); resumed = true }
        harness.dispatcher.runReadyBatch()
        assertFalse(resumed)
        assertEquals(10_000_000uL, harness.dispatcher.nextDeadlineNanos())
        harness.now = 9_999_999uL
        assertEquals(0, harness.dispatcher.runReadyBatch())
        harness.now++
        harness.dispatcher.runReadyBatch()
        assertTrue(resumed)
        assertNull(harness.dispatcher.nextDeadlineNanos())
        assertTrue(harness.wakeups > 0)
        harness.scope.cancel()
    }

    @Test
    fun cancellationReleasesTheEventWaiter() {
        val harness = Harness()
        val event = harness.dispatcher.createEvent()
        val cancelled = harness.scope.launch { event.await() }
        harness.dispatcher.runReadyBatch()
        cancelled.cancel()
        harness.dispatcher.runReadyBatch()
        var resumed = false
        val replacement = harness.scope.launch { event.await(); resumed = true }
        harness.dispatcher.runReadyBatch()
        assertFalse(resumed)
        event.signal()
        harness.dispatcher.runReadyBatch()
        assertTrue(resumed)
        assertTrue(replacement.isCompleted)
        assertTrue(cancelled.isCancelled)
        harness.scope.cancel()
    }

    private class Harness : CriticalSection(), MonotonicClock {
        var now = 0uL
        var wakeups = 0
        private var held = false
        val dispatcher = KernelDispatcher(this, this, { wakeups++ }, { throw it })
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        override fun nanoTime(): ULong = now

        override fun acquire(): ULong {
            check(!held)
            held = true
            return 0uL
        }

        override fun release(state: ULong) {
            check(held)
            held = false
        }
    }
}
