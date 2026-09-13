package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoundRobinPolicyTest {
    @Test
    fun distributesTasksAcrossSparseProcessorIds() {
        val policy = RoundRobinPolicy(listOf(2u, 17u, 255u), 3_000_000_000uL, 1_000u)
        val assignments = List(300) { policy.nextProcessor() }
        assertEquals(mapOf(2u to 100, 17u to 100, 255u to 100), assignments.groupingBy { it }.eachCount())
        assertEquals(listOf(2u, 17u, 255u, 2u), assignments.take(4))
        assertEquals(3_000_000uL, policy.quantumCycles)
    }

    @Test
    fun keepsItsProcessorSetStable() {
        val processors = mutableListOf(7)
        val policy = RoundRobinPolicy(processors, 1uL, 1u)
        processors.clear()
        repeat(4) { assertEquals(7, policy.nextProcessor()) }
    }

    @Test
    fun roundsUpWithoutOverflow() {
        assertEquals(4uL, RoundRobinPolicy(listOf(0), 10uL, 3u).quantumCycles)
        assertEquals(ULong.MAX_VALUE, RoundRobinPolicy(listOf(0), ULong.MAX_VALUE, 1u).quantumCycles)
        assertEquals(1uL shl 63, RoundRobinPolicy(listOf(0), ULong.MAX_VALUE, 2u).quantumCycles)
    }

    @Test
    fun rejectsUnusableConfigurations() {
        assertFailsWith<IllegalArgumentException> { RoundRobinPolicy(emptyList<Int>(), 1uL, 1u) }
        assertFailsWith<IllegalArgumentException> { RoundRobinPolicy(listOf(0), 1uL, 0u) }
        assertFailsWith<IllegalArgumentException> { RoundRobinPolicy(listOf(0), 0uL, 1u) }
        assertFailsWith<IllegalArgumentException> { RoundRobinPolicy(listOf(0), 999uL, 1_000u) }
    }
}
