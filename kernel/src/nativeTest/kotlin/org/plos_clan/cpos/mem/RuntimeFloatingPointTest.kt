package org.plos_clan.cpos.mem

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeFloatingPointTest {
    @Test
    fun classifiesNonFiniteValuesAndSamplesOverflowingIntervals() {
        assertTrue(Double.POSITIVE_INFINITY.isInfinite())
        assertTrue(Double.NEGATIVE_INFINITY.isInfinite())
        assertFalse(Double.NaN.isInfinite())
        assertTrue(Double.fromBits(0x7ff0000000000001L).isNaN())
        assertFalse(Double.MIN_VALUE.isNaN())
        val random = Random(37)
        repeat(64) {
            val value = random.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE)
            assertTrue(value.isFinite() && value >= -Double.MAX_VALUE && value < Double.MAX_VALUE)
        }
    }
}
