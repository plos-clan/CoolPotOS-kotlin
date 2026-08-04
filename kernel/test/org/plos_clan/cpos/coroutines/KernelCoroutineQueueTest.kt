package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
