@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class RoundRobinPolicy<T>(
    processors: List<T>,
    clockFrequency: ULong,
    frequencyHz: UInt,
) {
    init {
        require(processors.isNotEmpty())
        require(frequencyHz != 0u && clockFrequency >= frequencyHz)
    }

    val processors = processors.toList()
    val quantumCycles = clockFrequency / frequencyHz +
        if (clockFrequency % frequencyHz == 0uL) 0uL else 1uL

    private val cursor = AtomicInt(0)

    fun nextProcessor(): T {
        val index = cursor.fetchAndAdd(1).toUInt() % processors.size.toUInt()
        return processors[index.toInt()]
    }
}
