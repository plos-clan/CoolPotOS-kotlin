@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.fetchAndUpdate

private val INFINITY = ULong.MAX_VALUE

enum class ProcessResource(
    val number: Int,
    soft: ULong,
    hard: ULong,
) {
    CPU(0, INFINITY, INFINITY),
    FILE_SIZE(1, INFINITY, INFINITY),
    DATA(2, INFINITY, INFINITY),
    STACK(3, 8uL * 1024uL * 1024uL, INFINITY),
    CORE(4, 0uL, INFINITY),
    RSS(5, INFINITY, INFINITY),
    PROCESS_COUNT(6, 1_024uL, 1_024uL),
    OPEN_FILES(7, 1_024uL, 1_024uL),
    LOCKED_MEMORY(8, 64uL * 1024uL, 64uL * 1024uL),
    ADDRESS_SPACE(9, INFINITY, INFINITY),
    FILE_LOCKS(10, INFINITY, INFINITY),
    PENDING_SIGNALS(11, 1_024uL, 1_024uL),
    MESSAGE_QUEUE(12, 819_200uL, 819_200uL),
    NICE(13, 0uL, 0uL),
    REALTIME_PRIORITY(14, 0uL, 0uL),
    REALTIME_RUNTIME(15, INFINITY, INFINITY),
    ;

    val initial = ResourceLimit(soft, hard)

    companion object {
        fun from(number: Int): ProcessResource? = entries.firstOrNull { it.number == number }
    }
}

data class ResourceLimit(val soft: ULong, val hard: ULong) {
    init {
        require(soft <= hard)
    }
}

class ProcessLimits {
    private companion object {
        val defaults = ProcessResource.entries.map(ProcessResource::initial).toTypedArray()
    }

    private val values = AtomicReference(defaults)

    fun get(resource: ProcessResource): ResourceLimit = values.load()[resource.ordinal]

    fun replace(resource: ProcessResource, limit: ResourceLimit): ResourceLimit {
        val index = resource.ordinal
        val previous = values.fetchAndUpdate { current ->
            current.copyOf().also { it[index] = limit }
        }
        return previous[index]
    }

    internal fun inherit(source: ProcessLimits) {
        values.store(source.values.load())
    }
}
