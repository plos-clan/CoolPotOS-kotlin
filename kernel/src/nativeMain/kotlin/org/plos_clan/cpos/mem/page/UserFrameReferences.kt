@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem.page

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import platform.posix.memcpy

internal enum class FrameReleaseResult {
    RELEASED,
    REFERENCED,
    CONTENDED,
}

internal object UserFrameReferences {
    private const val PAGE_SHIFT = 12
    private const val SECTION_FRAME_BITS = 12
    private const val SECTION_FRAME_COUNT = 1 shl SECTION_FRAME_BITS
    private const val SECTION_FRAME_MASK = SECTION_FRAME_COUNT - 1

    private val sections = mutableMapOf<ULong, IntArray>()
    private val lock = IrqSpinLock()

    fun retain(frame: ULong) = lock.withLock {
        val counts = section(frame)
        val index = sectionIndex(frame)
        counts[index]++
    }

    fun shareAll(frames: Iterable<ULong>) = lock.withLock {
        var currentKey = ULong.MAX_VALUE
        var current = IntArray(0)
        frames.forEach { frame ->
            val key = sectionKey(frame)
            if (key != currentKey) {
                currentKey = key
                current = sections.getOrPut(key) { IntArray(SECTION_FRAME_COUNT) }
            }
            val index = sectionIndex(frame)
            current[index] = maxOf(current[index], 1) + 1
        }
    }

    fun release(frame: ULong): Boolean =
        lock.withLock { releaseLocked(frame) } &&
            BuddyFrameAllocator.free(frame, 1uL)

    fun releaseExclusive(frame: ULong): FrameReleaseResult {
        var result = FrameReleaseResult.REFERENCED
        if (!lock.tryWithLock {
            if (referenceCount(frame) <= 1) {
                check(releaseLocked(frame))
                result = FrameReleaseResult.RELEASED
            }
        }) return FrameReleaseResult.CONTENDED
        if (result != FrameReleaseResult.RELEASED) return result

        check(BuddyFrameAllocator.free(frame, 1uL)) {
            "Page cache released an invalid frame"
        }
        return result
    }

    fun releaseAll(frames: Iterable<ULong>) {
        val reclaimed = lock.withLock {
            val result = mutableListOf<ULong>()
            var currentKey = ULong.MAX_VALUE
            var current: IntArray? = null
            frames.forEach { frame ->
                val key = sectionKey(frame)
                if (key != currentKey) {
                    currentKey = key
                    current = sections[key]
                }
                val counts = current
                val index = sectionIndex(frame)
                if (counts == null || counts[index] <= 1) {
                    counts?.set(index, 0)
                    result += frame
                } else {
                    counts[index]--
                }
            }
            result
        }
        BuddyFrameAllocator.free(reclaimed)
    }

    fun isExclusive(frame: ULong): Boolean = lock.withLock {
        referenceCount(frame) <= 1
    }

    fun copyOnWrite(frame: ULong): ULong? {
        if (isExclusive(frame)) return frame
        val replacement = BuddyFrameAllocator.allocate(1uL)
        if (replacement == INVALID_FRAME) return null
        val source = Hhdm.toVirtualPointer<UByteVar>(frame)
        val destination = Hhdm.toVirtualPointer<UByteVar>(replacement)
        if (source == null || destination == null) {
            BuddyFrameAllocator.free(replacement, 1uL)
            return null
        }

        memcpy(destination, source, PAGE_SIZE_BYTES)
        lock.withLock {
            section(replacement)[sectionIndex(replacement)] = 1
        }
        return replacement
    }

    private fun releaseLocked(frame: ULong): Boolean {
        val counts = sections[sectionKey(frame)] ?: return true
        val index = sectionIndex(frame)
        if (counts[index] <= 1) {
            counts[index] = 0
            return true
        }
        counts[index]--
        return false
    }

    private fun referenceCount(frame: ULong): Int =
        sections[sectionKey(frame)]?.get(sectionIndex(frame)) ?: 0

    private fun section(frame: ULong): IntArray =
        sections.getOrPut(sectionKey(frame)) { IntArray(SECTION_FRAME_COUNT) }

    private fun sectionKey(frame: ULong): ULong =
        frame shr (PAGE_SHIFT + SECTION_FRAME_BITS)

    private fun sectionIndex(frame: ULong): Int =
        ((frame shr PAGE_SHIFT).toInt() and SECTION_FRAME_MASK)
}
