package org.plos_clan.cpos.drivers.acpi.apic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BspPeriodicTimerReadinessTest {
    @Test
    fun successfulUnmaskedConfigurationPublishesReadiness() {
        val readiness = BspPeriodicTimerReadiness()
        var configured = false

        assertTrue(
            readiness.initialize(
                timerVector = 32u,
                calibrate = { 1_000uL },
                configure = { vector, initialCount, masked ->
                    configured = vector == 32.toUByte() && initialCount == 1_000uL && !masked
                    configured
                },
            ),
        )
        assertTrue(configured)
        assertTrue(readiness.isReady)
    }

    @Test
    fun zeroCalibrationRejectsAndClearsEarlierReadiness() {
        val readiness = readyState()
        var configureCalled = false

        assertFalse(
            readiness.initialize(
                timerVector = 32u,
                calibrate = { 0uL },
                configure = { _, _, _ ->
                    configureCalled = true
                    true
                },
            ),
        )
        assertFalse(configureCalled)
        assertFalse(readiness.isReady)
    }

    @Test
    fun invalidVectorRejectsWithoutCalibrationOrPublication() {
        listOf(0u, 31u, UByte.MAX_VALUE.toUInt() + 1u).forEach { invalidVector ->
            val readiness = readyState()
            var calibrateCalled = false

            assertFalse(
                readiness.initialize(
                    timerVector = invalidVector,
                    calibrate = {
                        calibrateCalled = true
                        1_000uL
                    },
                    configure = { _, _, _ -> true },
                ),
            )
            assertFalse(calibrateCalled)
            assertFalse(readiness.isReady)
        }
    }

    @Test
    fun failedRegisterConfigurationDoesNotPublishReadiness() {
        val readiness = readyState()

        assertFalse(
            readiness.initialize(
                timerVector = 32u,
                calibrate = { 1_000uL },
                configure = { _, _, _ -> false },
            ),
        )
        assertFalse(readiness.isReady)
    }

    @Test
    fun apTimerConfigurationCannotPublishBspReadiness() {
        val readiness = BspPeriodicTimerReadiness()

        assertTrue(readiness.configureAp { true })
        assertFalse(readiness.isReady)
    }

    private fun readyState(): BspPeriodicTimerReadiness = BspPeriodicTimerReadiness().also { readiness ->
        assertTrue(
            readiness.initialize(
                timerVector = 32u,
                calibrate = { 1_000uL },
                configure = { _, _, _ -> true },
            ),
        )
        assertTrue(readiness.isReady)
    }
}
