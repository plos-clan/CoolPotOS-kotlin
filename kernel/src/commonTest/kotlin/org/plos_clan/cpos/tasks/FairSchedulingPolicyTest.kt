package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FairSchedulingPolicyTest {
    @Test
    fun honorsAffinityWhenBothSampledProcessorsAreExcluded() {
        val policy = FairSchedulingPolicy(listOf(0, 1, 2, 3), 1_000_000uL, 1_000u)
        repeat(20) {
            val cpu = policy.nextProcessor({ it == 3 }) { error("only one eligible CPU") }
            assertEquals(3, cpu)
        }
    }

    @Test
    fun avoidsTheBusierCandidateWithBoundedSampling() {
        val policy = FairSchedulingPolicy(listOf(2, 7, 19), 1uL, 1u)
        var samples = 0
        repeat(30) {
            val cpu = policy.nextProcessor {
                samples++
                if (it == 7) 100uL else 0uL
            }
            assertTrue(cpu != 7)
        }
        assertEquals(60, samples)
        val single = FairSchedulingPolicy(listOf(7), 1uL, 1u)
        assertEquals(7, single.nextProcessor { error("single CPU needs no load sampling") })
    }

    @Test
    fun separatesWeightFromExecutionQuantum() {
        val policy = FairSchedulingPolicy(listOf(0), 1_000_000_000uL, 1_000u)
        for (nice in NicePriority.MIN until NicePriority.MAX) {
            assertTrue(policy.weight(nice) > policy.weight(nice + 1))
        }
        assertEquals(1024u, policy.weight(0))
        assertEquals(1_000_000uL, policy.quantumCycles)
        assertEquals(15u, policy.weight(NicePriority.MAX))
    }

    @Test
    fun distributesTasksAcrossSparseProcessorIds() {
        val policy = FairSchedulingPolicy(listOf(2u, 17u, 255u), 3_000_000_000uL, 1_000u)
        val assignments = List(300) { policy.nextProcessor { 0uL } }
        val counts = assignments.groupingBy { it }.eachCount()
        assertEquals(mapOf(2u to 100, 17u to 100, 255u to 100), counts)
        assertEquals(listOf(2u, 17u, 255u, 2u), assignments.take(4))
        assertEquals(3_000_000uL, policy.quantumCycles)
    }

    @Test
    fun keepsItsProcessorSetStable() {
        val processors = mutableListOf(7)
        val policy = FairSchedulingPolicy(processors, 1uL, 1u)
        processors.clear()
        repeat(4) { assertEquals(7, policy.nextProcessor { 0uL }) }
    }

    @Test
    fun roundsUpWithoutOverflow() {
        val rounded = FairSchedulingPolicy(listOf(0), 10uL, 3u)
        val maximum = FairSchedulingPolicy(listOf(0), ULong.MAX_VALUE, 1u)
        val half = FairSchedulingPolicy(listOf(0), ULong.MAX_VALUE, 2u)
        assertEquals(4uL, rounded.quantumCycles)
        assertEquals(ULong.MAX_VALUE, maximum.quantumCycles)
        assertEquals(1uL shl 63, half.quantumCycles)
    }

    @Test
    fun rejectsUnusableConfigurations() {
        assertFailsWith<IllegalArgumentException> {
            FairSchedulingPolicy(emptyList<Int>(), 1uL, 1u)
        }
        assertFailsWith<IllegalArgumentException> {
            FairSchedulingPolicy(listOf(0), 1uL, 0u)
        }
        assertFailsWith<IllegalArgumentException> {
            FairSchedulingPolicy(listOf(0), 0uL, 1u)
        }
        assertFailsWith<IllegalArgumentException> {
            FairSchedulingPolicy(listOf(0), 999uL, 1_000u)
        }
    }
}
