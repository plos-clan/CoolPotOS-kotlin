@file:OptIn(ExperimentalAtomicApi::class, ExperimentalUnsignedTypes::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class FairSchedulingPolicy<T>(
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

    fun weight(nice: Int): UInt {
        val priority = nice.coerceIn(NicePriority.MIN, NicePriority.MAX)
        return weights[priority - NicePriority.MIN]
    }

    private val weights = uintArrayOf(
        88761u, 71755u, 56483u, 46273u, 36291u, 29154u, 23254u, 18705u, 14949u, 11916u,
        9548u, 7620u, 6100u, 4904u, 3906u, 3121u, 2501u, 1991u, 1586u, 1277u,
        1024u, 820u, 655u, 526u, 423u, 335u, 272u, 215u, 172u, 137u,
        110u, 87u, 70u, 56u, 45u, 36u, 29u, 23u, 18u, 15u,
    )

    fun nextProcessor(eligible: (T) -> Boolean = { true }, load: (T) -> ULong): T {
        if (processors.size == 1) {
            val processor = processors[0]
            require(eligible(processor))
            return processor
        }
        val count = processors.size.toUInt()
        val index = cursor.fetchAndAdd(1).toUInt() % count
        val first = processors[index.toInt()]
        val secondIndex = ((index + 1u) % count).toInt()
        val second = processors[secondIndex]
        val firstEligible = eligible(first)
        val secondEligible = eligible(second)
        if (firstEligible && secondEligible) {
            val firstLoad = load(first)
            val secondLoad = load(second)
            return if (firstLoad <= secondLoad) first else second
        }
        if (firstEligible) return first
        if (secondEligible) return second
        for (offset in 2 until processors.size) {
            val candidateIndex = ((index + offset.toUInt()) % count).toInt()
            val candidate = processors[candidateIndex]
            if (eligible(candidate)) return candidate
        }
        error("No eligible processor")
    }
}
