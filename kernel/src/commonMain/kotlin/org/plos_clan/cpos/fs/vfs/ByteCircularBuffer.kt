package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.BufferFaultPolicy
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

internal class ByteCircularBuffer(capacity: Int) : BufferSource {
    private var bytes = ByteArray(1)
    private var readOffset = 0
    private var writeOffset = 0

    var capacity: Int = capacity
        private set

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
        val start = (readOffset + sourceOffset) % bytes.size
        val firstChunk = minOf(count, bytes.size - start)
        bytes.copyInto(destination, destinationOffset, start, start + firstChunk)
        if (firstChunk != count) {
            bytes.copyInto(destination, destinationOffset + firstChunk, 0, count - firstChunk)
        }
        return count
    }

    override fun copyTo(sourceOffset: Int, destination: BufferDestination, destinationOffset: Int, count: Int): Int {
        require(sourceOffset >= 0 && count >= 0 && sourceOffset <= size - count)
        val start = (readOffset + sourceOffset) % bytes.size
        val firstChunk = minOf(count, bytes.size - start)
        val copied = destination.copyFrom(destinationOffset, bytes, start, firstChunk)
        return if (copied == firstChunk && copied < count) {
            copied + destination.copyFrom(destinationOffset + copied, bytes, 0, count - copied)
        } else copied
    }

    fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        peek: Boolean = false,
    ): Int {
        val requested = minOf(count, size)
        val firstChunk = minOf(requested, bytes.size - readOffset)
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
        ensureStorage(size + requested)
        val firstChunk = minOf(requested, bytes.size - writeOffset)
        var transferred = source.copyTo(offset, bytes, writeOffset, firstChunk)
        val remainingChunk = requested - firstChunk
        if (transferred == firstChunk && remainingChunk != 0) {
            transferred += source.copyTo(offset + firstChunk, bytes, 0, remainingChunk)
        }
        writeOffset += transferred
        if (writeOffset >= bytes.size) writeOffset -= bytes.size
        size += transferred
        return transferred
    }

    fun discard(count: Int): Int {
        val discarded = minOf(count.coerceAtLeast(0), size)
        readOffset += discarded
        if (readOffset >= bytes.size) readOffset -= bytes.size
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
        this.capacity = maxOf(this.capacity, capacity)
    }

    private fun ensureStorage(required: Int) {
        if (required <= bytes.size) return
        val doubled = bytes.size.toLong() * 2
        val expanded = maxOf(required.toLong(), doubled).coerceAtMost(capacity.toLong())
        val replacement = ByteArray(expanded.toInt())
        val firstChunk = minOf(size, bytes.size - readOffset)
        bytes.copyInto(replacement, 0, readOffset, readOffset + firstChunk)
        if (firstChunk < size) bytes.copyInto(replacement, firstChunk, 0, size - firstChunk)
        bytes = replacement
        readOffset = 0
        writeOffset = size
    }

    fun reserveWrite(count: Int): WriteReservation {
        require(count in 0..remaining)
        ensureStorage(size + count)
        return WriteReservation(writeOffset, count)
    }

    inner class WriteReservation internal constructor(
        private val start: Int,
        val capacity: Int,
    ) : BufferDestination {
        val destination = PreparedBufferDestination(this)

        override fun prepareWrite(
            offset: Int,
            count: Int,
            faultPolicy: BufferFaultPolicy,
        ): PreparedBufferDestination? =
            if (validRange(offset, count)) destination else null

        override fun copyFrom(
            destinationOffset: Int,
            source: ByteArray,
            sourceOffset: Int,
            count: Int,
        ): Int {
            require(validRange(destinationOffset, count))
            require(sourceOffset >= 0 && sourceOffset <= source.size - count)
            val target = (start + destinationOffset) % bytes.size
            val firstChunk = minOf(count, bytes.size - target)
            source.copyInto(bytes, target, sourceOffset, sourceOffset + firstChunk)
            if (firstChunk != count) {
                source.copyInto(bytes, 0, sourceOffset + firstChunk, sourceOffset + count)
            }
            return count
        }

        override fun copyFrom(
            destinationOffset: Int,
            source: BufferSource,
            sourceOffset: Int,
            count: Int,
        ): Int {
            require(validRange(destinationOffset, count))
            val target = (start + destinationOffset) % bytes.size
            val firstChunk = minOf(count, bytes.size - target)
            val copied = source.copyTo(sourceOffset, bytes, target, firstChunk)
            return if (copied == firstChunk && copied < count) {
                copied + source.copyTo(sourceOffset + copied, bytes, 0, count - copied)
            } else copied
        }

        override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
            require(validRange(destinationOffset, count))
            val target = (start + destinationOffset) % bytes.size
            val firstChunk = minOf(count, bytes.size - target)
            bytes.fill(value, target, target + firstChunk)
            if (firstChunk != count) bytes.fill(value, 0, count - firstChunk)
            return count
        }

        fun commit(count: Int) {
            require(count in 0..capacity && start == writeOffset)
            writeOffset = (writeOffset + count) % bytes.size
            size += count
        }

        private fun validRange(offset: Int, count: Int): Boolean =
            offset >= 0 && count >= 0 && offset <= capacity - count
    }
}
