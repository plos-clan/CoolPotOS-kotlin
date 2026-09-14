package org.plos_clan.cpos.module.elf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElfLayoutTest {
    @Test
    fun addsAddressesWithoutWrapping() {
        assertEquals(0uL, ElfLayout.checkedAdd(0uL, 0uL))
        assertEquals(ULong.MAX_VALUE, ElfLayout.checkedAdd(ULong.MAX_VALUE - 1uL, 1uL))
        assertNull(ElfLayout.checkedAdd(ULong.MAX_VALUE, 1uL))
        assertNull(ElfLayout.checkedAdd(ULong.MAX_VALUE - 1uL, 2uL))
    }

    @Test
    fun checksFileRangesWithoutUnderflow() {
        assertTrue(ElfLayout.fitsInFile(offset = 4uL, size = 6uL, fileSize = 10uL))
        assertTrue(ElfLayout.fitsInFile(offset = 10uL, size = 0uL, fileSize = 10uL))
        assertFalse(ElfLayout.fitsInFile(offset = 10uL, size = 1uL, fileSize = 10uL))
        assertFalse(ElfLayout.fitsInFile(offset = 11uL, size = 0uL, fileSize = 10uL))
        assertTrue(
            ElfLayout.fitsInFile(
                offset = ULong.MAX_VALUE,
                size = 0uL,
                fileSize = ULong.MAX_VALUE,
            ),
        )
    }

    @Test
    fun recognizesPowersOfTwo() = with(ElfLayout) {
        assertFalse(0uL.isPowerOfTwo())
        assertTrue(1uL.isPowerOfTwo())
        assertTrue(2uL.isPowerOfTwo())
        assertTrue(4096uL.isPowerOfTwo())
        assertTrue((1uL shl 63).isPowerOfTwo())
        assertFalse(3uL.isPowerOfTwo())
        assertFalse(ULong.MAX_VALUE.isPowerOfTwo())
    }
}
