package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KernelCoroutineQueueTest {
    @Test
    fun immediateTasksAreClaimedFifoUpToLimit() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<Int>()
        repeat(65) { value ->
            queue.enqueue(Runnable { executed += value })
        }

        val firstClaim = queue.claimReady(nowNanos = 0uL, limit = 64)

        assertEquals(64, firstClaim.size)
        assertTrue(queue.hasImmediateWork())
        assertTrue(executed.isEmpty())
        firstClaim.forEach(Runnable::run)
        assertEquals((0 until 64).toList(), executed)

        val secondClaim = queue.claimReady(nowNanos = 0uL, limit = 64)

        assertEquals(1, secondClaim.size)
        assertFalse(queue.hasImmediateWork())
        secondClaim.forEach(Runnable::run)
        assertEquals((0 until 65).toList(), executed)
    }

    @Test
    fun delayedTasksSortByDeadlineThenInsertionSequence() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        queue.schedule(10uL, 2, Runnable { executed += "later" })
        queue.schedule(10uL, 1, Runnable { executed += "equal-first" })
        queue.schedule(10uL, 1, Runnable { executed += "equal-second" })

        assertTrue(queue.claimReady(1_000_009uL, 64).isEmpty())
        assertTrue(executed.isEmpty())

        queue.claimReady(1_000_010uL, 64).forEach(Runnable::run)
        assertEquals(listOf("equal-first", "equal-second"), executed)

        queue.claimReady(2_000_010uL, 64).forEach(Runnable::run)
        assertEquals(listOf("equal-first", "equal-second", "later"), executed)
    }

    @Test
    fun disposingPendingDelayedTaskIsIdempotentAndPreventsExecution() {
        val queue = KernelCoroutineQueue()
        var executed = false
        val task = queue.schedule(0uL, 1, Runnable { executed = true })

        assertTrue(queue.dispose(task))
        assertFalse(queue.dispose(task))
        assertTrue(queue.claimReady(ULong.MAX_VALUE, 64).isEmpty())
        assertFalse(executed)
    }

    @Test
    fun deadlineArithmeticSaturatesAtUnsignedLongMaximum() {
        val queue = KernelCoroutineQueue()

        val task = queue.schedule(
            nowNanos = ULong.MAX_VALUE - 5uL,
            delayMillis = Long.MAX_VALUE,
            runnable = Runnable {},
        )

        assertEquals(ULong.MAX_VALUE, task.deadlineNanos)
    }

    @Test
    fun nonPositiveTimeoutIsImmediatelyClaimableAtNow() {
        val queue = KernelCoroutineQueue()
        var executed = false

        val task = queue.schedule(123uL, -1, Runnable { executed = true })

        assertEquals(123uL, task.deadlineNanos)
        val claimed: List<Runnable> = queue.claimReady(123uL, 1)
        assertEquals(1, claimed.size)
        assertFalse(executed)
        claimed.single().run()
        assertTrue(executed)
    }

    @Test
    fun claimLimitMustBePositive() {
        val queue = KernelCoroutineQueue()

        assertFailsWith<IllegalArgumentException> {
            queue.claimReady(nowNanos = 0uL, limit = 0)
        }
    }

    @Test
    fun idleClaimReturnsCanonicalEmptyList() {
        val queue = KernelCoroutineQueue()
        val disposed = queue.schedule(0uL, 0, Runnable {})
        queue.dispose(disposed)

        val claimed = queue.claimReady(nowNanos = 0uL, limit = 1)

        assertSame(emptyList<Runnable>(), claimed)
    }

    @Test
    fun deeperDelayedHeapClaimsEveryTaskInDeadlineOrder() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<Long>()
        val mixedDeadlines = listOf(7L, 2L, 6L, 1L, 5L, 3L, 4L)
        mixedDeadlines.forEach { delayMillis ->
            queue.schedule(0uL, delayMillis, Runnable { executed += delayMillis })
        }

        queue.claimReady(7_000_000uL, mixedDeadlines.size).forEach(Runnable::run)

        assertEquals((1L..7L).toList(), executed)
    }

    @Test
    fun disposingNonRootDelayedTaskPreservesRemainingHeapOrder() {
        val queue = KernelCoroutineQueue()
        val executed = mutableListOf<String>()
        queue.schedule(0uL, 1, Runnable { executed += "first" })
        val disposed = queue.schedule(0uL, 7, Runnable { executed += "disposed" })
        queue.schedule(0uL, 3, Runnable { executed += "third" })
        queue.schedule(0uL, 2, Runnable { executed += "second" })
        queue.schedule(0uL, 6, Runnable { executed += "last" })

        assertTrue(queue.dispose(disposed))
        queue.claimReady(ULong.MAX_VALUE, 64).forEach(Runnable::run)

        assertEquals(listOf("first", "second", "third", "last"), executed)
    }

    @Test
    fun claimedDelayedTaskCannotBeDisposedOrClaimedAgain() {
        val queue = KernelCoroutineQueue()
        var executions = 0
        val task = queue.schedule(0uL, 1, Runnable { executions++ })

        val firstClaim = queue.claimReady(1_000_000uL, 1)
        assertFalse(queue.dispose(task))
        firstClaim.single().run()
        val secondClaim = queue.claimReady(ULong.MAX_VALUE, 1)

        assertTrue(secondClaim.isEmpty())
        assertEquals(1, executions)
    }

    @Test
    fun deadlineSaturatesWhenMillisecondsToNanosecondsOverflows() {
        val queue = KernelCoroutineQueue()
        val overflowMillis = (ULong.MAX_VALUE / 1_000_000uL + 1uL).toLong()

        val task = queue.schedule(0uL, overflowMillis, Runnable {})

        assertEquals(ULong.MAX_VALUE, task.deadlineNanos)
    }

    @Test
    fun deadlineSaturatesWhenNanosecondsAdditionOverflows() {
        val queue = KernelCoroutineQueue()

        val task = queue.schedule(ULong.MAX_VALUE - 999_999uL, 1, Runnable {})

        assertEquals(ULong.MAX_VALUE, task.deadlineNanos)
    }

    @Test
    fun sequenceResetsAfterDelayedHeapIsDrained() {
        val queue = KernelCoroutineQueue()
        val first = queue.schedule(0uL, 1, Runnable {})
        queue.claimReady(1_000_000uL, 1)

        val afterDrain = queue.schedule(0uL, 1, Runnable {})

        assertEquals(0uL, first.sequence)
        assertEquals(0uL, afterDrain.sequence)
    }

    @Test
    fun clearDropsImmediateWorkDisposesDelayedTasksAndResetsSequence() {
        val queue = KernelCoroutineQueue()
        queue.enqueue(Runnable { throw AssertionError("cleared immediate task ran") })
        val firstDelayed = queue.schedule(0uL, 1, Runnable {})
        val secondDelayed = queue.schedule(0uL, 2, Runnable {})

        queue.clear()

        assertFalse(queue.hasImmediateWork())
        assertEquals(DelayedTaskState.DISPOSED, firstDelayed.state)
        assertEquals(DelayedTaskState.DISPOSED, secondDelayed.state)
        assertTrue(queue.claimReady(ULong.MAX_VALUE, 64).isEmpty())
        assertEquals(0uL, queue.schedule(0uL, 1, Runnable {}).sequence)
    }
}
