package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

internal class ByteCircularBuffer(capacity: Int) {
    private var bytes = ByteArray(capacity)
    private var readOffset = 0
    private var writeOffset = 0

    val capacity: Int
        get() = bytes.size

    var size = 0
        private set

    val remaining: Int
        get() = capacity - size

    init {
        require(capacity > 0)
    }

    fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean = false,
    ): Int {
        val requested = minOf(count, size)
        val firstChunk = minOf(requested, capacity - readOffset)
        var transferred = destination.copyFrom(offset, bytes, readOffset, firstChunk)
        val remainingChunk = requested - firstChunk
        if (transferred == firstChunk && remainingChunk != 0) {
            transferred += destination.copyFrom(offset + firstChunk, bytes, 0, remainingChunk)
        }
        if (!peek) discard(transferred)
        return transferred
    }

    fun write(source: PreparedBufferSource, offset: Int, count: Int): Int {
        val requested = minOf(count, remaining)
        val firstChunk = minOf(requested, capacity - writeOffset)
        var transferred = source.copyTo(offset, bytes, writeOffset, firstChunk)
        val remainingChunk = requested - firstChunk
        if (transferred == firstChunk && remainingChunk != 0) {
            transferred += source.copyTo(offset + firstChunk, bytes, 0, remainingChunk)
        }
        writeOffset += transferred
        if (writeOffset >= capacity) writeOffset -= capacity
        size += transferred
        return transferred
    }

    fun discard(count: Int): Int {
        val discarded = minOf(count.coerceAtLeast(0), size)
        readOffset += discarded
        if (readOffset >= capacity) readOffset -= capacity
        size -= discarded
        return discarded
    }

    fun clear() {
        readOffset = 0
        writeOffset = 0
        size = 0
    }

    fun ensureCapacity(capacity: Int) {
        require(capacity > 0)
        if (capacity <= bytes.size) return
        val replacement = ByteArray(capacity)
        val firstChunk = minOf(size, bytes.size - readOffset)
        bytes.copyInto(replacement, 0, readOffset, readOffset + firstChunk)
        if (firstChunk < size) bytes.copyInto(replacement, firstChunk, 0, size - firstChunk)
        bytes = replacement
        readOffset = 0
        writeOffset = size
    }
}
