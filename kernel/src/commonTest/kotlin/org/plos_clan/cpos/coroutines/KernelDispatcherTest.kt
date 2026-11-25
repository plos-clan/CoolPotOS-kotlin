package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.time.MonotonicClock
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelDispatcherTest {
    @Test
    fun selfSubmissionAcrossBatchesWakesOnlyAnIdleWorker() {
        val harness = Harness()
        val job = harness.scope.launch { repeat(128) { yield() } }
        assertTrue(harness.runReady())
        assertTrue(harness.runReady())
        assertFalse(harness.runReady())
        assertTrue(job.isCompleted)
        assertEquals(1, harness.wakeups)
        harness.worker.submit {}
        assertEquals(2, harness.wakeups)
        assertNull(harness.worker.steal())
    }

    @Test
    fun keepsTimersLocalAndSpillsOnlyToALighterWorker() {
        val local = Harness()
        val peer = Harness()
        val dispatcher = KernelDispatcher(local, { peer.worker }) { local.worker }
        repeat(4) { dispatcher.dispatch(EmptyCoroutineContext) {} }
        assertEquals(3, local.worker.load)
        assertEquals(1, peer.worker.load)
        dispatcher.scheduleAt(42uL) {}
        assertEquals(42uL, local.worker.nextDeadline())
        assertNull(peer.worker.nextDeadline())
    }

    @Test
    fun stealsOnlyPublishedReadyWorkAndWakesForEarlierTimers() {
        val harness = Harness()
        val worker = harness.worker
        val order = mutableListOf<Int>()
        val late = worker.scheduleAt(50uL) { order += 50 }
        worker.scheduleAt(20uL) { order += 20 }
        worker.scheduleAt(40uL) { order += 40 }
        worker.submit { order += 1 }
        worker.submit { order += 2 }
        assertEquals(3, harness.wakeups)
        checkNotNull(worker.steal()).run()
        late.dispose()
        harness.now = 40uL
        harness.runReady()
        assertEquals(listOf(2, 1, 20, 40), order)
        assertEquals(0, worker.load)
        assertNull(worker.nextDeadline())
    }

    @Test
    fun deliversOnTheWorkerWithoutRedispatchingOrTakingOverAnotherDispatcher() {
        val owner = Harness()
        val peer = Harness()
        val event = owner.dispatcher.createEvent()
        val order = mutableListOf<String>()
        owner.scope.launch { event.await(); order += "local" }
        owner.runReady()
        event.signal()
        owner.worker.submit { order += "queued" }
        assertTrue(order.isEmpty())
        owner.runReady()
        assertEquals(listOf("local", "queued"), order)
        peer.scope.launch { event.await(); order += "peer" }
        peer.runReady()
        event.signal()
        owner.runReady()
        assertEquals(2, order.size)
        peer.runReady()
        assertEquals("peer", order.last())
    }

    @Test
    fun signalsBeforeAwaitCoalesceWithoutWakingAWorker() {
        val harness = Harness()
        val event = harness.dispatcher.createEvent()
        repeat(8) { event.signal() }
        assertEquals(0, harness.wakeups)
        var count = 0
        harness.scope.launch { repeat(2) { event.await(); count++ } }
        harness.runReady()
        assertEquals(1, count)
        repeat(8) { event.signal() }
        harness.runReady()
        assertEquals(2, count)
        harness.scope.cancel()
    }

    @Test
    fun resumesOnlyAfterTheInjectedDeadline() {
        val harness = Harness()
        var resumed = false
        harness.scope.launch { delay(10); resumed = true }
        harness.runReady()
        assertFalse(resumed)
        assertEquals(10_000_000uL, harness.worker.nextDeadline())
        harness.now = 9_999_999uL
        assertFalse(harness.runReady())
        harness.now++
        harness.runReady()
        assertTrue(resumed)
        assertNull(harness.worker.nextDeadline())
        assertTrue(harness.wakeups > 0)
        harness.scope.cancel()
    }

    @Test
    fun cancellationAfterSignalPreservesThePendingWakeup() {
        val harness = Harness()
        val event = harness.dispatcher.createEvent()
        val first = harness.scope.launch { event.await() }
        harness.runReady()
        event.signal()
        first.cancel()
        var received = 0
        harness.scope.launch { repeat(2) { event.await(); received++ } }
        harness.runReady()
        assertEquals(1, received)
        event.signal()
        harness.runReady()
        assertEquals(2, received)
        harness.scope.cancel()
    }

    @Test
    fun cancellationReleasesTheEventWaiter() {
        val harness = Harness()
        val event = harness.dispatcher.createEvent()
        val cancelled = harness.scope.launch { event.await() }
        harness.runReady()
        cancelled.cancel()
        harness.runReady()
        var resumed = false
        val replacement = harness.scope.launch { event.await(); resumed = true }
        harness.runReady()
        assertFalse(resumed)
        event.signal()
        harness.runReady()
        assertTrue(resumed)
        assertTrue(replacement.isCompleted)
        assertTrue(cancelled.isCancelled)
        harness.scope.cancel()
    }

    private class Harness : CriticalSection(), MonotonicClock {
        var now = 0uL
        var wakeups = 0
        private var held = false
        val worker = KernelWorker(this, this) { wakeups++ }
        val dispatcher = KernelDispatcher(this, { worker }) { null }
        fun runReady(): Boolean = worker.runReadyBatch({ null }) { throw it }
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
