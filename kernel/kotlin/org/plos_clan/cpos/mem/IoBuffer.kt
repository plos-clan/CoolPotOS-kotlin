@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

interface BufferSource {
    fun prepareRead(offset: Int, count: Int): PreparedBufferSource?

    fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int
}

interface BufferDestination {
    fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination?

    fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int

    fun copyFrom(destinationOffset: Int, source: CPointer<UByteVar>, count: Int): Int

    fun fill(destinationOffset: Int, count: Int, value: Byte = 0): Int
}

interface IoBuffer : BufferSource, BufferDestination

value class PreparedBufferSource internal constructor(
    private val source: BufferSource,
) {
    fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int = source.copyTo(sourceOffset, destination, destinationOffset, count)
}

value class PreparedBufferDestination internal constructor(
    private val destination: BufferDestination,
) {
    fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int = destination.copyFrom(destinationOffset, source, sourceOffset, count)

    fun copyFrom(destinationOffset: Int, source: CPointer<UByteVar>, count: Int): Int =
        destination.copyFrom(destinationOffset, source, count)

    fun fill(destinationOffset: Int, count: Int, value: Byte = 0): Int =
        destination.fill(destinationOffset, count, value)
}

class ByteArrayBuffer(private val bytes: ByteArray) : IoBuffer {
    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (validRange(offset, count)) PreparedBufferSource(this) else null

    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (validRange(offset, count)) PreparedBufferDestination(this) else null

    override fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int {
        require(validRange(sourceOffset, count))
        require(
            destinationOffset >= 0 && count >= 0 &&
                destinationOffset <= destination.size - count,
        )
        bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + count)
        return count
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int {
        require(validRange(destinationOffset, count))
        require(
            sourceOffset >= 0 && count >= 0 &&
                sourceOffset <= source.size - count,
        )
        source.copyInto(bytes, destinationOffset, sourceOffset, sourceOffset + count)
        return count
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: CPointer<UByteVar>,
        count: Int,
    ): Int {
        require(validRange(destinationOffset, count))
        bytes.usePinned { destination ->
            memcpy(destination.addressOf(destinationOffset), source, count.toULong())
        }
        return count
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
        require(validRange(destinationOffset, count))
        bytes.fill(value, destinationOffset, destinationOffset + count)
        return count
    }

    private fun validRange(offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset <= bytes.size - count
}
