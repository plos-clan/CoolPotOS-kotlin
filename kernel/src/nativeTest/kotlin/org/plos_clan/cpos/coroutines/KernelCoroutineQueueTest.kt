package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelCoroutineQueueTest {
    @Test
    fun claimsImmediateTasksInFifoBatches() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        queue.enqueue { executed += "first" }
        queue.enqueue { executed += "second" }
        queue.enqueue { executed += "third" }

        assertTrue(queue.hasImmediateWork())
        queue.claimReady(0uL, 2).forEach { it.run() }
        assertEquals(listOf("first", "second"), executed)
        assertTrue(queue.hasImmediateWork())

        queue.claimReady(0uL, 2).forEach { it.run() }
        assertEquals(listOf("first", "second", "third"), executed)
        assertFalse(queue.hasImmediateWork())
    }

    @Test
    fun ordersDelayedTasksByDeadlineThenSubmission() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        val last = queue.scheduleAt(20uL) { executed += "last" }
        val first = queue.scheduleAt(10uL) { executed += "first" }
        val second = queue.scheduleAt(10uL) { executed += "second" }

        assertEquals(10uL, queue.nextDeadline())
        assertTrue(queue.claimReady(9uL, 3).isEmpty())
        queue.claimReady(10uL, 3).forEach { it.run() }

        assertEquals(listOf("first", "second"), executed)
        assertEquals(DelayedTaskState.CLAIMED, first.state)
        assertEquals(DelayedTaskState.CLAIMED, second.state)
        assertEquals(DelayedTaskState.PENDING, last.state)
        assertEquals(20uL, queue.nextDeadline())
    }

    @Test
    fun skipsDisposedTasks() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        val discarded = queue.scheduleAt(10uL) { executed += "discarded" }
        val retained = queue.scheduleAt(20uL) { executed += "retained" }

        assertTrue(queue.dispose(discarded))
        assertFalse(queue.dispose(discarded))
        assertEquals(20uL, queue.nextDeadline())
        queue.claimReady(20uL, 1).forEach { it.run() }

        assertEquals(listOf("retained"), executed)
        assertEquals(DelayedTaskState.CLAIMED, retained.state)
        assertFalse(queue.dispose(retained))
    }

    @Test
    fun saturatesDeadlinesAndValidatesBatchSize() {
        val queue = KernelCoroutineQueue()
        val noop = Runnable {}

        assertEquals(42uL, queue.schedule(42uL, 0, noop).deadlineNanos)
        assertEquals(
            ULong.MAX_VALUE,
            queue.schedule(ULong.MAX_VALUE - 500_000uL, 1, noop).deadlineNanos,
        )
        assertEquals(
            ULong.MAX_VALUE,
            queue.schedule(1uL, Long.MAX_VALUE, noop).deadlineNanos,
        )
        assertFailsWith<IllegalArgumentException> { queue.claimReady(0uL, 0) }
    }
}
