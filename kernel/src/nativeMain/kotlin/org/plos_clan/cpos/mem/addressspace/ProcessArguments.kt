package org.plos_clan.cpos.mem.addressspace

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal data class ProcessArguments(
    val start: ULong,
    val end: ULong,
    val environmentStart: ULong,
    val environmentEnd: ULong,
) {
    enum class Boundary(val option: ULong) {
        START(8uL),
        END(9uL),
        ENVIRONMENT_START(10uL),
        ENVIRONMENT_END(11uL),
    }

    fun relocate(boundary: Boundary, address: ULong): ProcessArguments = when (boundary) {
        Boundary.START -> copy(start = address)
        Boundary.END -> copy(end = address)
        Boundary.ENVIRONMENT_START -> copy(environmentStart = address)
        Boundary.ENVIRONMENT_END -> copy(environmentEnd = address)
    }

    fun read(space: AddressSpace, offset: Long, count: Int): ByteArray {
        if (offset < 0 || count <= 0 || start >= end || environmentEnd == 0uL) return byteArrayOf()
        val position = offset.toULong()
        val extended = environmentStart == end && environmentEnd >= environmentStart
        val limit = if (extended) environmentEnd else end
        if (position >= limit - start) return byteArrayOf()
        val terminatorMemory = UserMemory(space, end - 1uL)
        val lastByte = terminatorMemory.copyFromUser(1)?.firstOrNull()
        val title = lastByte != null && lastByte != 0.toByte()
        if (title && position >= PAGE_SIZE_BYTES) return byteArrayOf()
        if (!title && position >= end - start) return byteArrayOf()
        val address = if (title) start else start + position
        val requested = minOf(count.toULong(), end - address, PAGE_SIZE_BYTES)
        val length = if (title) PAGE_SIZE_BYTES else requested
        val buffer = ByteArray(length.toInt())
        val memory = UserMemory(space, address)
        val copied = memory.copyTo(0, buffer, 0, buffer.size)
        if (!title && copied == buffer.size) return buffer
        if (!title) return buffer.copyOf(copied)
        val terminator = buffer.indexOf(0)
        val available = if (terminator in 0 until copied) terminator + 1 else copied
        if (position >= available.toULong()) return byteArrayOf()
        val remaining = minOf(available.toULong(), limit - start) - position
        val size = minOf(count.toULong(), remaining).toInt()
        val first = offset.toInt()
        return buffer.copyOfRange(first, first + size)
    }

    companion object {
        val EMPTY = ProcessArguments(0uL, 0uL, 0uL, 0uL)
    }
}
