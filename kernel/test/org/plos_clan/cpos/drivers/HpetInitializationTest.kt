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
                0x10uL to 0uL,
                0xF0uL to 0uL,
                0x100uL to ((20uL shl 9) or (1uL shl 2)),
                0x10uL to 1uL,
            ),
            writes,
        )
    }

    @Test
    fun reinitializingEnabledCounterDisablesBeforeResetAndPreservesGeneralConfigBits() {
        val period = 100_000_000u
        val disabledGeneralConfig = 0x2uL // LEG_RT_CNF
        val enabledGeneralConfig = 0x3uL // LEG_RT_CNF | ENABLE_CNF
        val programmedTimerConfig = (20uL shl 9) or (1uL shl 2)
        var generalConfig = disabledGeneralConfig
        var mainCounter = 0uL
        var recordEvents = false
        val events = mutableListOf<String>()

        fun initialize(mappedBase: ULong) {
            Hpet.initializeMapped(
                mappedBase = mappedBase,
                read32 = { offset ->
                    if (recordEvents) events += "read32:$offset"
                    if (offset == 0x4uL) period else 0u
                },
                read64 = { offset ->
                    if (recordEvents) events += "read64:$offset"
                    when (offset) {
                        0uL -> 1uL shl 13
                        0x10uL -> generalConfig
                        0x100uL -> 1uL shl (32 + 20)
                        0xF0uL -> mainCounter
                        else -> 0uL
                    }
                },
                write64 = { offset, value ->
                    if (recordEvents) events += "write64:$offset=$value"
                    when (offset) {
                        0x10uL -> generalConfig = value
                        0xF0uL -> mainCounter = value
                    }
                },
                configureClock = { base, configuredPeriod ->
                    if (recordEvents) events += "configure:$base=$configuredPeriod"
                },
                log = {
                    if (recordEvents) events += "log"
                },
            )
        }

        initialize(0x1000uL)
        assertEquals(enabledGeneralConfig, generalConfig)
        assertTrue(Hpet.isReady)

        mainCounter = 123uL
        recordEvents = true
        initialize(0x2000uL)

        assertEquals(
            listOf(
                "read64:0",
                "read32:4",
                "read64:16",
                "write64:16=$disabledGeneralConfig",
                "write64:240=0",
                "read64:256",
                "write64:256=$programmedTimerConfig",
                "configure:8192=$period",
                "write64:16=$enabledGeneralConfig",
                "read64:240",
                "log",
            ),
            events,
        )
        assertEquals(enabledGeneralConfig, generalConfig)
        assertEquals(0uL, mainCounter)
        assertTrue(Hpet.isReady)
    }
}
