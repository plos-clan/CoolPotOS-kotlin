package org.plos_clan.cpos.coroutines

import org.plos_clan.cpos.time.MonotonicClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelCoroutineQueueTest {
    @Test
    fun pollsImmediateTasksInFifoOrder() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        val noTimer = { error("Ready work must not read the clock") }
        queue.enqueue { executed += "first" }
        queue.enqueue { executed += "second" }
        queue.enqueue { executed += "third" }

        assertEquals(3, queue.readyCount)
        repeat(2) { checkNotNull(queue.poll(noTimer)).run() }
        assertEquals(listOf("first", "second"), executed)
        assertEquals(1, queue.readyCount)

        checkNotNull(queue.poll(noTimer)).run()
        assertEquals(listOf("first", "second", "third"), executed)
        assertNull(queue.poll(noTimer))
    }

    @Test
    fun ordersDelayedTasksByDeadlineThenSubmission() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        queue.scheduleAt(20uL) { executed += "last" }
        val first = queue.scheduleAt(10uL) { executed += "first" }
        val second = queue.scheduleAt(10uL) { executed += "second" }

        assertEquals(10uL, queue.nextDeadline())
        assertNull(queue.poll { 9uL })
        repeat(2) { checkNotNull(queue.poll { 10uL }).run() }

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
        checkNotNull(queue.poll { 20uL }).run()

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
                checkNotNull(queue.poll { ULong.MAX_VALUE }).run()
            }
            assertEquals(retained, executed)
            assertNull(queue.nextDeadline())
            assertNull(queue.poll { ULong.MAX_VALUE })
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
        assertNull(queue.poll { ULong.MAX_VALUE - 1u })
        checkNotNull(queue.poll { ULong.MAX_VALUE }).run()
        assertTrue(fired)
        assertNull(queue.nextDeadline())
    }

    @Test
    fun saturatesDeadlines() {
        var now = 42uL
        val clock = MonotonicClock { now }
        assertEquals(now, clock.deadlineAfterMillis(0))
        assertEquals(ULong.MAX_VALUE, clock.deadlineAfterMillis(Long.MAX_VALUE))
        now = ULong.MAX_VALUE - 500_000uL
        assertEquals(ULong.MAX_VALUE, clock.deadlineAfterMillis(1))
    }
}
