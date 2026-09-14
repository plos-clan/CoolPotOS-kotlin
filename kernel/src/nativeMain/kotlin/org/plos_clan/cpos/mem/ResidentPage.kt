@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal class ResidentPage private constructor(val frame: ULong) : NativeMemory() {
    override val pointer: CPointer<UByteVar>
        get() = checkNotNull(Hhdm.toVirtualPointer(frame))

    fun read(destination: PreparedBufferDestination, offset: Int, pageOffset: Int, count: Int): Int =
        destination.copyFrom(offset, this, pageOffset, count)

    fun write(source: PreparedBufferSource, offset: Int, pageOffset: Int, count: Int): Int =
        source.copyTo(offset, this, pageOffset, count)

    fun release() = UserFrameReferences.release(frame)

    override val size: Int
        get() = PAGE_SIZE_BYTES.toInt()

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
