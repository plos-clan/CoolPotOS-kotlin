package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeightedRoundRobinPolicyTest {
    @Test
    fun avoidsTheBusierCandidateWithBoundedSampling() {
        val policy = WeightedRoundRobinPolicy(listOf(2, 7, 19), 1uL, 1u)
        var samples = 0
        repeat(30) {
            val cpu = policy.nextProcessor {
                samples++
                if (it == 7) 100uL else 0uL
            }
            assertTrue(cpu != 7)
        }
        assertEquals(60, samples)
        val single = WeightedRoundRobinPolicy(listOf(7), 1uL, 1u)
        assertEquals(7, single.nextProcessor { error("single CPU needs no load sampling") })
    }

    @Test
    fun appliesNiceWeightsWithoutZeroOrOverflowingQuanta() {
        val policy = WeightedRoundRobinPolicy(listOf(0), 1_000_000_000uL, 1_000u)
        for (nice in NicePriority.MIN until NicePriority.MAX) {
            assertTrue(policy.quantumCycles(nice) > policy.quantumCycles(nice + 1))
        }
        assertEquals(1_000_000uL, policy.quantumCycles(0))
        assertEquals(107_375uL, policy.quantumCycles(10))
        val slow = WeightedRoundRobinPolicy(listOf(0), 1uL, 1u)
        assertEquals(1uL, slow.quantumCycles(19))
        val fast = WeightedRoundRobinPolicy(listOf(0), ULong.MAX_VALUE - 1uL, 1u)
        assertEquals(ULong.MAX_VALUE - 1uL, fast.quantumCycles(0))
        assertEquals(ULong.MAX_VALUE, fast.quantumCycles(-20))
    }

    @Test
    fun distributesTasksAcrossSparseProcessorIds() {
        val policy = WeightedRoundRobinPolicy(listOf(2u, 17u, 255u), 3_000_000_000uL, 1_000u)
        val assignments = List(300) { policy.nextProcessor { 0uL } }
        assertEquals(mapOf(2u to 100, 17u to 100, 255u to 100), assignments.groupingBy { it }.eachCount())
        assertEquals(listOf(2u, 17u, 255u, 2u), assignments.take(4))
        assertEquals(3_000_000uL, policy.quantumCycles())
    }

    @Test
    fun keepsItsProcessorSetStable() {
        val processors = mutableListOf(7)
        val policy = WeightedRoundRobinPolicy(processors, 1uL, 1u)
        processors.clear()
        repeat(4) { assertEquals(7, policy.nextProcessor { 0uL }) }
    }

    @Test
    fun roundsUpWithoutOverflow() {
        assertEquals(4uL, WeightedRoundRobinPolicy(listOf(0), 10uL, 3u).quantumCycles())
        assertEquals(ULong.MAX_VALUE, WeightedRoundRobinPolicy(listOf(0), ULong.MAX_VALUE, 1u).quantumCycles())
        assertEquals(1uL shl 63, WeightedRoundRobinPolicy(listOf(0), ULong.MAX_VALUE, 2u).quantumCycles())
    }

    @Test
    fun rejectsUnusableConfigurations() {
        assertFailsWith<IllegalArgumentException> { WeightedRoundRobinPolicy(emptyList<Int>(), 1uL, 1u) }
        assertFailsWith<IllegalArgumentException> { WeightedRoundRobinPolicy(listOf(0), 1uL, 0u) }
        assertFailsWith<IllegalArgumentException> { WeightedRoundRobinPolicy(listOf(0), 0uL, 1u) }
        assertFailsWith<IllegalArgumentException> { WeightedRoundRobinPolicy(listOf(0), 999uL, 1_000u) }
    }
}
