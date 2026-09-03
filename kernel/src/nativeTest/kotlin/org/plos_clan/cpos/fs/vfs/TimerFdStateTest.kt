package org.plos_clan.cpos.fs.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerFdStateTest {
    @Test
    fun oneShotExpiresOnceAndDisarms() {
        val state = TimerFdState()
        state.replace(deadlineNanos = 100uL, intervalNanos = 0uL)

        assertFalse(state.advance(99uL))
        assertEquals(TimerFdSetting(0uL, 1uL), state.snapshot(99uL))
        assertTrue(state.advance(100uL))
        assertEquals(1uL, state.expirations)
        assertNull(state.deadlineNanos)
        assertEquals(1uL, state.consume())
        assertEquals(0uL, state.consume())
        assertFalse(state.advance(200uL))
    }

    @Test
    fun periodicTimerCoalescesMissedExpirationsWithoutDrift() {
        val state = TimerFdState()
        state.replace(deadlineNanos = 100uL, intervalNanos = 10uL)

        assertTrue(state.advance(135uL))
        assertEquals(4uL, state.expirations)
        assertEquals(140uL, state.deadlineNanos)
        assertEquals(TimerFdSetting(10uL, 5uL), state.snapshot(135uL))

        assertFalse(state.advance(139uL))
        assertTrue(state.advance(140uL))
        assertEquals(5uL, state.consume())
        assertEquals(150uL, state.deadlineNanos)
    }

    @Test
    fun replacementClearsUnreadExpirationsAndPreservesDisarmedInterval() {
        val state = TimerFdState()
        state.replace(deadlineNanos = 10uL, intervalNanos = 5uL)
        assertTrue(state.advance(20uL))
        assertEquals(3uL, state.expirations)

        state.replace(deadlineNanos = null, intervalNanos = 7uL)

        assertEquals(0uL, state.expirations)
        assertNull(state.deadlineNanos)
        assertEquals(TimerFdSetting(7uL, 0uL), state.snapshot(100uL))
    }

    @Test
    fun expirationCountSaturatesAndUnrepresentablePeriodicDeadlineDisarms() {
        val state = TimerFdState()
        state.replace(deadlineNanos = 0uL, intervalNanos = 1uL)

        assertTrue(state.advance(ULong.MAX_VALUE))
        assertEquals(ULong.MAX_VALUE, state.expirations)
        assertNull(state.deadlineNanos)
    }
}
