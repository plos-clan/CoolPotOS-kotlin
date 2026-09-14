@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memmove
import platform.posix.memset

abstract class NativeMemorySource : NativeBuffer() {
    protected abstract val pointer: CPointer<UByteVar>
    abstract val size: Int

    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (validRange(offset, count)) PreparedBufferSource(this) else null

    override fun copyTo(sourceOffset: Int, destination: ByteArray, destinationOffset: Int, count: Int): Int {
        require(validRange(sourceOffset, count))
        require(destinationOffset >= 0 && count >= 0 && destinationOffset <= destination.size - count)
        if (count != 0) destination.usePinned {
            memmove(it.addressOf(destinationOffset), checkNotNull(pointer + sourceOffset), count.toULong())
        }
        return count
    }

    override fun copyToNative(sourceOffset: Int, destination: CPointer<UByteVar>, count: Int): Int {
        require(validRange(sourceOffset, count))
        if (count != 0) memmove(destination, checkNotNull(pointer + sourceOffset), count.toULong())
        return count
    }

    protected fun validRange(offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset <= size - count
}

abstract class NativeMemory : NativeMemorySource(), IoBuffer {
    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (validRange(offset, count)) PreparedBufferDestination(this) else null

    override fun copyFrom(destinationOffset: Int, source: ByteArray, sourceOffset: Int, count: Int): Int {
        require(validRange(destinationOffset, count))
        require(sourceOffset >= 0 && count >= 0 && sourceOffset <= source.size - count)
        if (count != 0) source.usePinned {
            memmove(checkNotNull(pointer + destinationOffset), it.addressOf(sourceOffset), count.toULong())
        }
        return count
    }

    override fun copyFrom(destinationOffset: Int, source: BufferSource, sourceOffset: Int, count: Int): Int {
        require(validRange(destinationOffset, count))
        if (count == 0) return 0
        return if (source is NativeBuffer) source.copyToNative(sourceOffset, checkNotNull(pointer + destinationOffset), count)
        else source.copyTo(sourceOffset, this, destinationOffset, count)
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
        require(validRange(destinationOffset, count))
        if (count != 0) memset(checkNotNull(pointer + destinationOffset), value.toInt(), count.toULong())
        return count
    }

}
