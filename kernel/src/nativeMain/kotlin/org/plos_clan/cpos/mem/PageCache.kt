@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.page.FrameReleaseResult
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memcpy

interface PageCacheProvider {
    val cacheSource: PageCacheSource?
}

interface PageCacheSource : PageCacheProvider {
    override val cacheSource: PageCacheSource
        get() = this

    val identity: Any
        get() = this

    fun read(offset: ULong, destination: ByteArray): Int
}

internal enum class PageCacheFailure {
    OUT_OF_MEMORY,
    IO_ERROR,
}

internal value class PageCacheAcquireResult private constructor(private val value: ULong) {
    val isSuccess: Boolean
        get() = value < OUT_OF_MEMORY

    val frame: ULong
        get() {
            check(isSuccess)
            return value
        }

    val failure: PageCacheFailure
        get() = when (value) {
            OUT_OF_MEMORY -> PageCacheFailure.OUT_OF_MEMORY
            else -> PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val IO_ERROR = 0xffff_ffff_ffff_ffffuL
        private const val OUT_OF_MEMORY = 0xffff_ffff_ffff_fffeuL

        fun acquired(frame: ULong) = PageCacheAcquireResult(frame)
        fun failed(failure: PageCacheFailure) = PageCacheAcquireResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
            },
        )
    }
}

internal value class PageCacheReadResult private constructor(private val value: Int) {
    val isSuccess: Boolean
        get() = value >= 0

    val bytes: Int
        get() = value.coerceAtLeast(0)

    val failure: PageCacheFailure
        get() = if (value == OUT_OF_MEMORY) {
            PageCacheFailure.OUT_OF_MEMORY
        } else {
            PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val OUT_OF_MEMORY = -1
        private const val IO_ERROR = -2

        fun completed(bytes: Int) = PageCacheReadResult(bytes)
        fun failed(failure: PageCacheFailure) = PageCacheReadResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
            },
        )
    }
}

internal data class PageCacheStatistics(
    val cachedBytes: ULong,
    val reclaimableBytes: ULong,
)

private data class PageCacheKey(
    val identity: Any,
    val offset: ULong,
)

private class CachedPage(
    val key: PageCacheKey,
    val frame: ULong,
    var referenced: Boolean = true,
)

private enum class ClockScanResult {
    RECLAIMED,
    UNAVAILABLE,
    CONTENDED,
}

internal object PageCache : FrameReclaimer {
    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<PageCacheKey, CachedPage>()
    private val clock = ArrayDeque<CachedPage>()

    init {
        BuddyFrameAllocator.register(this)
    }

    fun acquire(
        source: PageCacheSource,
        offset: ULong,
        scratch: ByteArray,
    ): PageCacheAcquireResult {
        require(offset.isPageAligned() && scratch.size >= PAGE_SIZE_BYTES.toInt())
        val key = PageCacheKey(source.identity, offset)
        val cached = retainCached(key)
        return if (cached != INVALID_FRAME) {
            PageCacheAcquireResult.acquired(cached)
        } else {
            load(source, key, scratch)
        }
    }

    fun read(
        source: PageCacheSource,
        sourceOffset: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): PageCacheReadResult {
        require(destinationOffset >= 0 && count >= 0)
        if (count == 0) return PageCacheReadResult.completed(0)
        if (sourceOffset > ULong.MAX_VALUE - count.toULong()) {
            return PageCacheReadResult.failed(PageCacheFailure.IO_ERROR)
        }

        var copied = 0
        var scratch: ByteArray? = null
        val identity = source.identity
        while (copied < count) {
            val position = sourceOffset + copied.toULong()
            val pageOffset = position.alignDown(PAGE_SIZE_BYTES)
            val key = PageCacheKey(identity, pageOffset)
            var frame = retainCached(key)
            if (frame == INVALID_FRAME) {
                val buffer = scratch ?: ByteArray(PAGE_SIZE_BYTES.toInt()).also { scratch = it }
                val loaded = load(source, key, buffer)
                if (!loaded.isSuccess) {
                    return if (copied == 0) {
                        PageCacheReadResult.failed(loaded.failure)
                    } else {
                        PageCacheReadResult.completed(copied)
                    }
                }
                frame = loaded.frame
            }

            val sourcePointer = Hhdm.toVirtualPointer<UByteVar>(frame)
            if (sourcePointer == null) {
                release(frame)
                return if (copied == 0) {
                    PageCacheReadResult.failed(PageCacheFailure.IO_ERROR)
                } else {
                    PageCacheReadResult.completed(copied)
                }
            }
            val pageIndex = (position - pageOffset).toInt()
            val chunk = minOf(count - copied, PAGE_SIZE_BYTES.toInt() - pageIndex)
            val transferred = destination.copyFrom(
                destinationOffset + copied,
                checkNotNull(sourcePointer + pageIndex),
                chunk,
            )
            release(frame)
            copied += transferred
            if (transferred < chunk) break
        }
        return PageCacheReadResult.completed(copied)
    }

    fun release(frame: ULong) {
        UserFrameReferences.release(frame)
    }

    override fun reclaim(target: ULong): ULong {
        var reclaimed = 0uL
        while (reclaimed < target && scanClock() == ClockScanResult.RECLAIMED) {
            reclaimed++
        }
        return reclaimed
    }

    fun statistics(): PageCacheStatistics {
        var reclaimableFrames = 0uL
        var cachedFrames = 0uL
        lock.withLock {
            cachedFrames = pages.size.toULong()
            for (page in clock) {
                if (UserFrameReferences.isExclusive(page.frame)) reclaimableFrames++
            }
        }
        return PageCacheStatistics(
            cachedBytes = cachedFrames * PAGE_SIZE_BYTES,
            reclaimableBytes = reclaimableFrames * PAGE_SIZE_BYTES,
        )
    }

    private fun retainCached(key: PageCacheKey): ULong = lock.withLock {
        val page = pages[key] ?: return@withLock INVALID_FRAME
        page.referenced = true
        UserFrameReferences.retain(page.frame)
        page.frame
    }

    private fun load(
        source: PageCacheSource,
        key: PageCacheKey,
        scratch: ByteArray,
    ): PageCacheAcquireResult {
        val frame = BuddyFrameAllocator.allocate(1uL)
        if (frame == INVALID_FRAME) {
            return PageCacheAcquireResult.failed(PageCacheFailure.OUT_OF_MEMORY)
        }
        val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
        if (destination == null) {
            BuddyFrameAllocator.free(frame, 1uL)
            return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
        }

        scratch.fill(0, 0, PAGE_SIZE_BYTES.toInt())
        val count = source.read(key.offset, scratch)
        if (count !in 0..PAGE_SIZE_BYTES.toInt()) {
            BuddyFrameAllocator.free(frame, 1uL)
            return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
        }
        scratch.usePinned { data ->
            memcpy(destination, data.addressOf(0), PAGE_SIZE_BYTES)
        }
        return PageCacheAcquireResult.acquired(publish(CachedPage(key, frame)))
    }

    private fun publish(candidate: CachedPage): ULong {
        val frame = candidate.frame
        UserFrameReferences.retain(frame)
        val acquired = lock.withLock {
            val existing = pages[candidate.key]
            if (existing != null) {
                existing.referenced = true
                UserFrameReferences.retain(existing.frame)
                existing.frame
            } else {
                pages[candidate.key] = candidate
                clock.addLast(candidate)
                UserFrameReferences.retain(frame)
                frame
            }
        }
        if (acquired != frame) UserFrameReferences.release(frame)
        return acquired
    }

    private fun scanClock(): ClockScanResult {
        var result = ClockScanResult.CONTENDED
        lock.tryWithLock { result = scanClockLocked() }
        return result
    }

    private fun scanClockLocked(): ClockScanResult {
        val resident = clock.size
        var remaining = if (resident > Int.MAX_VALUE / 2) Int.MAX_VALUE else resident * 2
        while (remaining > 0) {
            remaining--
            val page = clock.removeFirst()
            if (page.referenced) {
                page.referenced = false
                clock.addLast(page)
                continue
            }

            val release = UserFrameReferences.releaseExclusive(page.frame)
            if (release == FrameReleaseResult.RELEASED) {
                check(pages.remove(page.key) === page)
                return ClockScanResult.RECLAIMED
            }
            clock.addLast(page)
            if (release == FrameReleaseResult.CONTENDED) return ClockScanResult.CONTENDED
        }
        return ClockScanResult.UNAVAILABLE
    }
}
