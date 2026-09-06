@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import platform.posix.memmove
import platform.posix.memset

/** One owned frame, shared directly with user page tables and never reclaimed as clean cache. */
internal class ResidentPage private constructor(val frame: ULong) : BufferDestination {
    private val pointer: CPointer<UByteVar>
        get() = checkNotNull(Hhdm.toVirtualPointer(frame))

    fun read(destination: PreparedBufferDestination, offset: Int, pageOffset: Int, count: Int): Int =
        destination.copyFrom(offset, checkNotNull(pointer + pageOffset), count)

    fun write(source: PreparedBufferSource, offset: Int, pageOffset: Int, count: Int): Int =
        source.copyTo(offset, checkNotNull(pointer + pageOffset), count)

    fun release() = UserFrameReferences.release(frame)

    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (offset >= 0 && count >= 0 && offset <= PAGE_SIZE_BYTES.toInt() - count) {
            PreparedBufferDestination(this)
        } else null

    override fun copyFrom(destinationOffset: Int, source: ByteArray, sourceOffset: Int, count: Int): Int {
        if (count != 0) source.usePinned {
            memmove(checkNotNull(pointer + destinationOffset), it.addressOf(sourceOffset), count.toULong())
        }
        return count
    }

    override fun copyFrom(destinationOffset: Int, source: CPointer<UByteVar>, count: Int): Int {
        memmove(checkNotNull(pointer + destinationOffset), source, count.toULong())
        return count
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
        memset(checkNotNull(pointer + destinationOffset), value.toInt(), count.toULong())
        return count
    }

    companion object {
        fun allocate(): ResidentPage? {
            val frame = BuddyFrameAllocator.allocate(1uL)
            if (frame == INVALID_FRAME) return null
            try {
                return ResidentPage(frame).also {
                    it.fill(0, PAGE_SIZE_BYTES.toInt())
                    UserFrameReferences.retain(frame)
                }
            } catch (error: OutOfMemoryError) {
                BuddyFrameAllocator.free(frame, 1uL)
                throw error
            }
        }
    }
}
