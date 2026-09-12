package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        queue.scheduleAt(20uL) { executed += "last" }
        val first = queue.scheduleAt(10uL) { executed += "first" }
        val second = queue.scheduleAt(10uL) { executed += "second" }

        assertEquals(10uL, queue.nextDeadline())
        assertTrue(queue.claimReady(9uL, 3).isEmpty())
        queue.claimReady(10uL, 3).forEach { it.run() }

        assertEquals(listOf("first", "second"), executed)
        assertFalse(queue.dispose(first))
        assertFalse(queue.dispose(second))
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
        assertFalse(queue.dispose(retained))
    }

    @Test
    fun cancellingInteriorTasksPreservesDeadlineAndFifoOrder() {
        val random = Random(37)
        repeat(32) {
            val queue = KernelCoroutineQueue()
            val executed = mutableListOf<Int>()
            val tasks = List(256) { id ->
                queue.scheduleAt(random.nextInt(64).toULong()) { executed += id }
            }
            val cancelled = tasks.indices.shuffled(random).take(192).toSet()
            for (id in cancelled) assertTrue(queue.dispose(tasks[id]))

            val retained = tasks.indices.filter { it !in cancelled }
                .sortedWith(compareBy({ tasks[it].deadlineNanos }, { it }))
            for (id in retained) {
                assertEquals(tasks[id].deadlineNanos, queue.nextDeadline())
                queue.claimReady(ULong.MAX_VALUE, 1).single().run()
            }
            assertEquals(retained, executed)
            assertNull(queue.nextDeadline())
            assertTrue(queue.claimReady(ULong.MAX_VALUE, 1).isEmpty())
        }
    }

    @Test
    fun cancellingAllTasksAllowsQueueReuse() {
        val queue = KernelCoroutineQueue()
        val tasks = List(256) { deadline ->
            queue.scheduleAt(deadline.toULong()) { error("cancelled timer fired") }
        }
        for (task in tasks.shuffled(Random(14))) {
            assertTrue(queue.dispose(task))
            assertFalse(queue.dispose(task))
        }
        assertNull(queue.nextDeadline())

        var fired = false
        queue.scheduleAt(ULong.MAX_VALUE) { fired = true }
        assertTrue(queue.claimReady(ULong.MAX_VALUE - 1u, 1).isEmpty())
        queue.claimReady(ULong.MAX_VALUE, 1).single().run()
        assertTrue(fired)
        assertNull(queue.nextDeadline())
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
