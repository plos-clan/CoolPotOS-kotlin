@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.ULongVar
import org.plos_clan.cpos.utils.clear
import org.plos_clan.cpos.utils.toVirtualPointer

internal class PageTableAllocation {
    private val frames = mutableListOf<ULong>()

    fun allocate(): ULong? {
        val frame = BuddyFrameAllocator.allocate(1uL)
        if (frame == INVALID_FRAME) {
            println("Paging: failed to allocate page-table frame")
            return null
        }
        val table = frame.toVirtualPointer<ULongVar>() ?: run {
            BuddyFrameAllocator.free(frame, 1uL)
            return null
        }
        table.clear()
        frames += frame
        return frame
    }

    fun release() {
        for (index in frames.lastIndex downTo 0) {
            BuddyFrameAllocator.free(frames[index], 1uL)
        }
        frames.clear()
    }
}
