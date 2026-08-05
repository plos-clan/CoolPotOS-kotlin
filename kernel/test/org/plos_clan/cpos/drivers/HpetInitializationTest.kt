package org.plos_clan.cpos.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HpetInitializationTest {
    @Test
    fun counterSizeCapabilityRequiresBitThirteen() {
        assertFalse(hpetSupports64BitMainCounter(0uL))
        assertFalse(hpetSupports64BitMainCounter(1uL shl 12))
        assertTrue(hpetSupports64BitMainCounter(1uL shl 13))
        assertTrue(hpetSupports64BitMainCounter((1uL shl 13) or (1uL shl 63)))
    }

    @Test
    fun rejectedCounterClearsPublishedStateAndDoesNotConfigureRuntimeClock() {
        val period = 100_000_000u
        var capabilities = 1uL shl 13
        val writes = mutableListOf<Pair<ULong, ULong>>()
        val clockConfigurations = mutableListOf<Pair<ULong, UInt>>()

        fun initialize(mappedBase: ULong) {
            Hpet.initializeMapped(
                mappedBase = mappedBase,
                read32 = { offset -> if (offset == 0x4uL) period else 0u },
                read64 = { offset ->
                    when (offset) {
                        0uL -> capabilities
                        0x10uL -> 0uL
                        0x100uL -> 1uL shl (32 + 20)
                        else -> 0uL
                    }
                },
                write64 = { offset, value -> writes += offset to value },
                configureClock = { base, configuredPeriod ->
                    clockConfigurations += base to configuredPeriod
                },
                log = {},
            )
        }

        initialize(0x1000uL)
        assertTrue(Hpet.isReady)
        assertEquals(listOf(0x1000uL to period), clockConfigurations)

        capabilities = 0uL
        initialize(0x2000uL)

        assertFalse(Hpet.isReady)
        assertEquals(listOf(0x1000uL to period), clockConfigurations)
        assertEquals(
            listOf(
                0xF0uL to 0uL,
                0x10uL to 1uL,
                0x100uL to ((20uL shl 9) or (1uL shl 2)),
            ),
            writes,
        )
    }
}
