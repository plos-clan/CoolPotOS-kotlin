package org.plos_clan.cpos.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RealtimeClockTest {
    @Test
    fun convertsValidatedGregorianDatesToUnixTime() {
        assertEquals(0L, RealtimeClock.DateTime(1970, 1, 1, 0, 0, 0).toEpochSeconds())
        assertEquals(
            951_782_400L,
            RealtimeClock.DateTime(2000, 2, 29, 0, 0, 0).toEpochSeconds(),
        )
        assertEquals(
            1_787_714_391L,
            RealtimeClock.DateTime(2026, 8, 26, 3, 19, 51).toEpochSeconds(),
        )
        assertNull(RealtimeClock.DateTime(2100, 2, 29, 0, 0, 0).toEpochSeconds())
        assertNull(RealtimeClock.DateTime(1969, 12, 31, 23, 59, 59).toEpochSeconds())
    }

    @Test
    fun computesSaturatingRealtimeDurations() {
        val now = RealtimeClock.Instant(10, 900_000_000u)

        assertEquals(200_000_000uL, now.durationUntil(11, 100_000_000u))
        assertEquals(0uL, now.durationUntil(10, 900_000_000u))
        assertEquals(0uL, now.durationUntil(9, 999_999_999u))
        assertEquals(10_900_000_000uL, now.toNanoseconds())
        assertEquals(0uL, RealtimeClock.Instant(-1, 0u).toNanoseconds())
        assertEquals(ULong.MAX_VALUE, RealtimeClock.Instant(Long.MAX_VALUE, 0u).toNanoseconds())
        assertEquals(ULong.MAX_VALUE, RealtimeClock.Instant(0, 0u).durationUntil(Long.MAX_VALUE, 0u))
        assertFailsWith<IllegalArgumentException> {
            RealtimeClock.Instant(0, 1_000_000_000u)
        }
    }
}
