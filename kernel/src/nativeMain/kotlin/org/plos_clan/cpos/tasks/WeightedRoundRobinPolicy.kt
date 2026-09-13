@file:OptIn(ExperimentalAtomicApi::class, ExperimentalUnsignedTypes::class)

package org.plos_clan.cpos.tasks

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class WeightedRoundRobinPolicy<T>(
    processors: List<T>,
    clockFrequency: ULong,
    frequencyHz: UInt,
) {
    init {
        require(processors.isNotEmpty())
        require(frequencyHz != 0u && clockFrequency >= frequencyHz)
    }

    val processors = processors.toList()
    private val baseQuantum = clockFrequency / frequencyHz +
        if (clockFrequency % frequencyHz == 0uL) 0uL else 1uL

    private val quanta = ULongArray(NicePriority.MAX - NicePriority.MIN + 1) { index ->
        val nice = index + NicePriority.MIN
        if (nice == 0) baseQuantum else ceil(baseQuantum.toDouble() * 1.25.pow(-nice)).coerceAtLeast(1.0).toULong()
    }
    private val cursor = AtomicInt(0)

    fun quantumCycles(nice: Int = 0): ULong = quanta[nice.coerceIn(NicePriority.MIN, NicePriority.MAX) - NicePriority.MIN]

    fun nextProcessor(load: (T) -> ULong): T {
        if (processors.size == 1) return processors[0]
        val index = cursor.fetchAndAdd(1).toUInt() % processors.size.toUInt()
        val first = processors[index.toInt()]
        val second = processors[((index + 1u) % processors.size.toUInt()).toInt()]
        return if (load(first) <= load(second)) first else second
    }
}
