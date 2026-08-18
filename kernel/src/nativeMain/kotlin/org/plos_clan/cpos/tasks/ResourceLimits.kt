package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.utils.IrqSpinLock

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
    private val lock = IrqSpinLock()
    private val values = Array(ProcessResource.entries.size) {
        ProcessResource.entries[it].initial
    }

    fun get(resource: ProcessResource): ResourceLimit = lock.withLock {
        values[resource.ordinal]
    }

    fun replace(resource: ProcessResource, limit: ResourceLimit) = lock.withLock {
        values[resource.ordinal].also { values[resource.ordinal] = limit }
    }

    internal fun inherit(source: ProcessLimits) {
        val inherited = source.lock.withLock { source.values.copyOf() }
        lock.withLock { inherited.copyInto(values) }
    }
}
