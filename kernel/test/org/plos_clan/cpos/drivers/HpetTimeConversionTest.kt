package org.plos_clan.cpos.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HpetTimeConversionTest {
    @Test
    fun conversionRemainsMonotonicAcrossLegacyProductOverflowBoundary() {
        val period = 100_000_000u
        val lastTickBeforeLegacyOverflow = ULong.MAX_VALUE / period.toULong()

        val before = hpetTicksToNanoseconds(lastTickBeforeLegacyOverflow, period)
        val after = hpetTicksToNanoseconds(lastTickBeforeLegacyOverflow + 1uL, period)

        assertEquals(lastTickBeforeLegacyOverflow * 100uL, before)
        assertEquals(before + 100uL, after)
        assertTrue(after > before)
    }

    @Test
    fun conversionHandlesLargestCounterValueWithoutWrapping() {
        assertEquals(
            ULong.MAX_VALUE,
            hpetTicksToNanoseconds(ULong.MAX_VALUE, 1_000_000u),
        )
    }

    @Test
    fun conversionSaturatesWhenNanosecondsExceedUlongRange() {
        val largestUnsaturatedTick = ULong.MAX_VALUE / 100uL

        assertEquals(
            largestUnsaturatedTick * 100uL,
            hpetTicksToNanoseconds(largestUnsaturatedTick, 100_000_000u),
        )
        assertEquals(
            ULong.MAX_VALUE,
            hpetTicksToNanoseconds(largestUnsaturatedTick + 1uL, 100_000_000u),
        )
    }
}
