package org.plos_clan.cpos.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlignmentTest {
    @Test
    fun alignsToPowerOfTwoAndArbitraryBoundaries() {
        assertEquals(16uL, 13uL.alignUp(8uL))
        assertEquals(8uL, 13uL.alignDown(8uL))
        assertEquals(24uL, 19uL.alignUp(6uL))
        assertEquals(18uL, 19uL.alignDown(6uL))
        assertEquals(18uL, 18uL.alignUp(6uL))

        assertTrue(18uL.isAligned(6uL))
        assertFalse(19uL.isAligned(6uL))
        assertTrue(PAGE_SIZE_BYTES.isPageAligned())
        assertFalse((PAGE_SIZE_BYTES + 1uL).isPageAligned())
    }

    @Test
    fun handlesOverflowAndInvalidAlignment() {
        assertEquals(ULong.MAX_VALUE, ULong.MAX_VALUE.alignUp(1uL))
        assertNull(ULong.MAX_VALUE.alignUp(2uL))
        assertFalse(1uL.isAligned(0uL))
        assertFailsWith<IllegalArgumentException> { 1uL.alignUp(0uL) }
        assertFailsWith<IllegalArgumentException> { 1uL.alignDown(0uL) }
    }
}
