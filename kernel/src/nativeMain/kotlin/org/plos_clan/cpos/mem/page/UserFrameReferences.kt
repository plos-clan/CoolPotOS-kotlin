@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem.page

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal object UserFrameReferences {
    private inline fun <T> withCount(frame: ULong, action: (AtomicIntArray, Int) -> T): T {
        val counts = BuddyFrameAllocator.references(frame)
        val index = (frame / PAGE_SIZE_BYTES).toInt() and (counts.size - 1)
        return action(counts, index)
    }

    fun retain(frame: ULong) {
        withCount(frame) { counts, index -> counts.fetchAndAddAt(index, 1) }
    }

    fun release(frame: ULong): Boolean {
        val previous = withCount(frame) { counts, index -> counts.fetchAndAddAt(index, -1) }
        check(previous > 0)
        return previous == 1 && BuddyFrameAllocator.free(frame, 1uL)
    }

    fun releaseExclusive(frame: ULong): Boolean {
        val released = withCount(frame) { counts, index -> counts.compareAndSetAt(index, 1, 0) }
        if (released) check(BuddyFrameAllocator.free(frame, 1uL))
        return released
    }

    fun isExclusive(frame: ULong): Boolean = withCount(frame) { counts, index ->
        counts.loadAt(index) == 1
    }

    fun copyOnWrite(frame: ULong): ULong? {
        if (isExclusive(frame)) return frame
        val replacement = BuddyFrameAllocator.allocate(1uL)
        if (replacement == INVALID_FRAME) return null
        val source = checkNotNull(Hhdm.toVirtualPointer<UByteVar>(frame))
        val destination = checkNotNull(Hhdm.toVirtualPointer<UByteVar>(replacement))
        memcpy(destination, source, PAGE_SIZE_BYTES)
        try {
            retain(replacement)
        } catch (failure: Throwable) {
            BuddyFrameAllocator.free(replacement, 1uL)
            throw failure
        }
        return replacement
    }
}
