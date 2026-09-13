package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NicePriorityTest {
    @Test
    fun clampsSignedArgumentsAndEncodesTheLinuxReturnValue() {
        val priority = NicePriority()
        assertEquals(20, priority.encoded)
        for ((requested, expected) in listOf(Int.MIN_VALUE to -20, -1 to -1, 0 to 0, Int.MAX_VALUE to 19)) {
            assertTrue(priority.set(requested, limit = 0uL, privileged = true))
            assertEquals(expected, priority.value)
            assertEquals(20 - expected, priority.encoded)
        }
    }

    @Test
    fun permitsLoweringPriorityButRequiresPermissionToRaiseIt() {
        val priority = NicePriority()
        assertTrue(priority.set(10, limit = 0uL, privileged = false))
        assertTrue(priority.set(10, limit = 0uL, privileged = false))
        assertFalse(priority.set(9, limit = 0uL, privileged = false))
        assertEquals(10, priority.value)
        assertTrue(priority.set(9, limit = 11uL, privileged = false))
        assertFalse(priority.set(8, limit = 11uL, privileged = false))
        assertTrue(priority.set(-20, limit = 40uL, privileged = false))
    }

    @Test
    fun comparesUnsignedLimitsWithoutOverflowAndClampsBeforeChecking() {
        val priority = NicePriority(19)
        assertFalse(priority.set(Int.MIN_VALUE, limit = 39uL, privileged = false))
        assertTrue(priority.set(Int.MIN_VALUE, limit = ULong.MAX_VALUE, privileged = false))
        assertEquals(-20, priority.value)
        assertTrue(priority.set(Int.MAX_VALUE, limit = 0uL, privileged = false))
        assertEquals(19, priority.value)
    }
}
