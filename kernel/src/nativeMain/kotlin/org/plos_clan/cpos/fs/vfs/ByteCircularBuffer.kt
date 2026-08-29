package org.plos_clan.cpos.fs.vfs

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal class ByteCircularBuffer(capacity: Int) : BufferSource {
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

    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (offset >= 0 && count >= 0 && offset <= size - count) {
            PreparedBufferSource(this)
        } else {
            null
        }

    override fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int {
        require(sourceOffset >= 0 && count >= 0 && sourceOffset <= size - count)
        require(
            destinationOffset >= 0 && destinationOffset <= destination.size - count,
        )
        val start = (readOffset + sourceOffset) % capacity
        val firstChunk = minOf(count, capacity - start)
        bytes.copyInto(destination, destinationOffset, start, start + firstChunk)
        if (firstChunk != count) {
            bytes.copyInto(destination, destinationOffset + firstChunk, 0, count - firstChunk)
        }
        return count
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

    fun reserveWrite(count: Int): WriteReservation {
        require(count in 0..remaining)
        return WriteReservation(writeOffset, count)
    }

    inner class WriteReservation internal constructor(
        private val start: Int,
        val capacity: Int,
    ) : BufferDestination {
        val destination = PreparedBufferDestination(this)

        override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
            if (validRange(offset, count)) destination else null

        override fun copyFrom(
            destinationOffset: Int,
            source: ByteArray,
            sourceOffset: Int,
            count: Int,
        ): Int {
            require(validRange(destinationOffset, count))
            require(sourceOffset >= 0 && sourceOffset <= source.size - count)
            val target = (start + destinationOffset) % this@ByteCircularBuffer.capacity
            val firstChunk = minOf(count, this@ByteCircularBuffer.capacity - target)
            source.copyInto(bytes, target, sourceOffset, sourceOffset + firstChunk)
            if (firstChunk != count) {
                source.copyInto(bytes, 0, sourceOffset + firstChunk, sourceOffset + count)
            }
            return count
        }

        override fun copyFrom(
            destinationOffset: Int,
            source: CPointer<UByteVar>,
            count: Int,
        ): Int {
            require(validRange(destinationOffset, count))
            val target = (start + destinationOffset) % this@ByteCircularBuffer.capacity
            val firstChunk = minOf(count, this@ByteCircularBuffer.capacity - target)
            bytes.usePinned { destination ->
                memcpy(destination.addressOf(target), source, firstChunk.toULong())
                if (firstChunk != count) {
                    memcpy(
                        destination.addressOf(0),
                        requireNotNull(source + firstChunk),
                        (count - firstChunk).toULong(),
                    )
                }
            }
            return count
        }

        override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
            require(validRange(destinationOffset, count))
            val target = (start + destinationOffset) % this@ByteCircularBuffer.capacity
            val firstChunk = minOf(count, this@ByteCircularBuffer.capacity - target)
            bytes.fill(value, target, target + firstChunk)
            if (firstChunk != count) bytes.fill(value, 0, count - firstChunk)
            return count
        }

        fun commit(count: Int) {
            require(count in 0..capacity && start == writeOffset)
            writeOffset = (writeOffset + count) % this@ByteCircularBuffer.capacity
            size += count
        }

        private fun validRange(offset: Int, count: Int): Boolean =
            offset >= 0 && count >= 0 && offset <= capacity - count
    }
}
