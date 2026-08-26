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

    val readAheadSize: Int
        get() = PAGE_SIZE_BYTES.toInt()

    fun read(offset: ULong, destination: ByteArray): Int

    companion object {
        internal const val READ_ERROR = Int.MIN_VALUE
        internal const val READ_INTERRUPTED = Int.MIN_VALUE + 1
    }
}

internal enum class PageCacheFailure {
    OUT_OF_MEMORY,
    IO_ERROR,
    INTERRUPTED,
}

internal class PageCacheAcquireResult private constructor(
    private val value: ULong,
    val validBytes: Int,
) {
    val isSuccess: Boolean
        get() = value < INTERRUPTED

    val frame: ULong
        get() {
            check(isSuccess)
            return value
        }

    val failure: PageCacheFailure
        get() = when (value) {
            OUT_OF_MEMORY -> PageCacheFailure.OUT_OF_MEMORY
            INTERRUPTED -> PageCacheFailure.INTERRUPTED
            else -> PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val IO_ERROR = 0xffff_ffff_ffff_ffffuL
        private const val OUT_OF_MEMORY = 0xffff_ffff_ffff_fffeuL
        private const val INTERRUPTED = 0xffff_ffff_ffff_fffduL

        fun acquired(frame: ULong, validBytes: Int) = PageCacheAcquireResult(frame, validBytes)
        fun failed(failure: PageCacheFailure) = PageCacheAcquireResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
                PageCacheFailure.INTERRUPTED -> INTERRUPTED
            },
            0,
        )
    }
}

internal value class PageCacheReadResult private constructor(private val value: Int) {
    val isSuccess: Boolean
        get() = value >= 0

    val bytes: Int
        get() = value.coerceAtLeast(0)

    val failure: PageCacheFailure
        get() = when (value) {
            OUT_OF_MEMORY -> PageCacheFailure.OUT_OF_MEMORY
            INTERRUPTED -> PageCacheFailure.INTERRUPTED
            else -> PageCacheFailure.IO_ERROR
        }

    companion object {
        private const val OUT_OF_MEMORY = -1
        private const val IO_ERROR = -2
        private const val INTERRUPTED = -3

        fun completed(bytes: Int) = PageCacheReadResult(bytes)
        fun failed(failure: PageCacheFailure) = PageCacheReadResult(
            when (failure) {
                PageCacheFailure.OUT_OF_MEMORY -> OUT_OF_MEMORY
                PageCacheFailure.IO_ERROR -> IO_ERROR
                PageCacheFailure.INTERRUPTED -> INTERRUPTED
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
    val validBytes: Int,
    var referenced: Boolean = true,
) {
    init {
        require(validBytes in 0..PAGE_SIZE_BYTES.toInt())
    }
}

private class PageLoad(val key: PageCacheKey) {
    var valid = true
}

private sealed interface PageLookup {
    data class Cached(val page: CachedPage) : PageLookup
    data class Missing(val load: PageLoad) : PageLookup
}

private enum class ClockScanResult {
    RECLAIMED,
    UNAVAILABLE,
    CONTENDED,
}

internal object PageCache : FrameReclaimer {
    private val lock = IrqSpinLock()
    private val pages = mutableMapOf<PageCacheKey, CachedPage>()
    private val clock = ArrayDeque<CachedPage>()
    private val loads = mutableSetOf<PageLoad>()

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
        return when (val lookup = lookup(key)) {
            is PageLookup.Cached -> PageCacheAcquireResult.acquired(
                lookup.page.frame,
                lookup.page.validBytes,
            )
            is PageLookup.Missing -> load(source, lookup.load, scratch)
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
            val page = when (val lookup = lookup(key)) {
                is PageLookup.Cached -> lookup.page
                is PageLookup.Missing -> {
                    val buffer = scratch ?: try {
                        ByteArray(maxOf(PAGE_SIZE_BYTES.toInt(), source.readAheadSize)).also {
                            scratch = it
                        }
                    } catch (_: OutOfMemoryError) {
                        try {
                            ByteArray(PAGE_SIZE_BYTES.toInt()).also { scratch = it }
                        } catch (_: OutOfMemoryError) {
                            cancel(lookup.load)
                            return if (copied == 0) {
                                PageCacheReadResult.failed(PageCacheFailure.OUT_OF_MEMORY)
                            } else {
                                PageCacheReadResult.completed(copied)
                            }
                        }
                    }
                    val loaded = load(source, lookup.load, buffer)
                    if (!loaded.isSuccess) {
                        return if (copied == 0) {
                            PageCacheReadResult.failed(loaded.failure)
                        } else {
                            PageCacheReadResult.completed(copied)
                        }
                    }
                    CachedPage(key, loaded.frame, loaded.validBytes)
                }
            }

            val sourcePointer = Hhdm.toVirtualPointer<UByteVar>(page.frame)
            if (sourcePointer == null) {
                release(page.frame)
                return if (copied == 0) {
                    PageCacheReadResult.failed(PageCacheFailure.IO_ERROR)
                } else {
                    PageCacheReadResult.completed(copied)
                }
            }
            val pageIndex = (position - pageOffset).toInt()
            val available = page.validBytes - pageIndex
            if (available <= 0) {
                release(page.frame)
                break
            }
            val chunk = minOf(count - copied, available)
            val transferred = destination.copyFrom(
                destinationOffset + copied,
                checkNotNull(sourcePointer + pageIndex),
                chunk,
            )
            release(page.frame)
            copied += transferred
            if (transferred < chunk) break
        }
        return PageCacheReadResult.completed(copied)
    }

    fun release(frame: ULong) {
        UserFrameReferences.release(frame)
    }

    fun invalidate(
        identity: Any,
        offset: ULong = 0uL,
        length: ULong? = null,
    ) {
        if (length == 0uL) return
        val end = length?.let { if (it > ULong.MAX_VALUE - offset) null else offset + it }
        val retired = mutableListOf<CachedPage>()
        lock.withLock {
            for (load in loads) {
                if (load.key.intersects(identity, offset, end)) load.valid = false
            }
            val iterator = pages.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.key.intersects(identity, offset, end)) continue
                retired += entry.value
                iterator.remove()
            }
            repeat(clock.size) {
                val page = clock.removeFirst()
                if (pages[page.key] === page) clock.addLast(page)
            }
        }
        retired.forEach { UserFrameReferences.release(it.frame) }
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

    private fun lookup(key: PageCacheKey): PageLookup = lock.withLock {
        val page = pages[key]
        if (page != null) {
            page.referenced = true
            UserFrameReferences.retain(page.frame)
            PageLookup.Cached(page)
        } else {
            PageLookup.Missing(PageLoad(key).also(loads::add))
        }
    }

    private fun load(
        source: PageCacheSource,
        load: PageLoad,
        scratch: ByteArray,
    ): PageCacheAcquireResult {
        val frame = BuddyFrameAllocator.allocate(1uL)
        if (frame == INVALID_FRAME) {
            cancel(load)
            return PageCacheAcquireResult.failed(PageCacheFailure.OUT_OF_MEMORY)
        }
        val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
        if (destination == null) {
            cancel(load)
            BuddyFrameAllocator.free(frame, 1uL)
            return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
        }

        val requestedSize = maxOf(PAGE_SIZE_BYTES.toInt(), source.readAheadSize)
        val buffer = if (scratch.size >= requestedSize) {
            scratch
        } else {
            try {
                ByteArray(requestedSize)
            } catch (_: OutOfMemoryError) {
                scratch
            }
        }
        val readAhead = reserveReadAhead(load.key, buffer.size)
        buffer.fill(0)
        val count = try {
            source.read(load.key.offset, buffer)
        } catch (_: Throwable) {
            cancel(load)
            readAhead.forEach(::cancel)
            BuddyFrameAllocator.free(frame, 1uL)
            return PageCacheAcquireResult.failed(PageCacheFailure.IO_ERROR)
        }
        if (count !in 0..buffer.size) {
            val failure = if (count == PageCacheSource.READ_INTERRUPTED) {
                PageCacheFailure.INTERRUPTED
            } else {
                PageCacheFailure.IO_ERROR
            }
            cancel(load)
            readAhead.forEach(::cancel)
            BuddyFrameAllocator.free(frame, 1uL)
            return PageCacheAcquireResult.failed(failure)
        }
        buffer.usePinned { data ->
            memcpy(destination, data.addressOf(0), PAGE_SIZE_BYTES)
        }
        readAhead.forEachIndexed { index, pending ->
            val sourceOffset = (index + 1) * PAGE_SIZE_BYTES.toInt()
            if (sourceOffset < count) {
                publishReadAhead(
                    pending,
                    buffer,
                    sourceOffset,
                    minOf(PAGE_SIZE_BYTES.toInt(), count - sourceOffset),
                )
            } else {
                cancel(pending)
            }
        }
        val page = publish(
            CachedPage(load.key, frame, minOf(count, PAGE_SIZE_BYTES.toInt())),
            load,
        )
        return PageCacheAcquireResult.acquired(page.frame, page.validBytes)
    }

    private fun reserveReadAhead(key: PageCacheKey, size: Int): List<PageLoad> =
        lock.withLock {
            buildList((size - 1) / PAGE_SIZE_BYTES.toInt()) {
                var offset = PAGE_SIZE_BYTES
                while (offset <= size.toULong() - PAGE_SIZE_BYTES &&
                    key.offset <= ULong.MAX_VALUE - offset
                ) {
                    add(PageLoad(PageCacheKey(key.identity, key.offset + offset)).also(loads::add))
                    offset += PAGE_SIZE_BYTES
                }
            }
        }

    private fun publishReadAhead(
        load: PageLoad,
        source: ByteArray,
        offset: Int,
        validBytes: Int,
    ) {
        val frame = BuddyFrameAllocator.allocate(1uL)
        val destination = if (frame == INVALID_FRAME) null else Hhdm.toVirtualPointer<UByteVar>(frame)
        if (destination == null) {
            cancel(load)
            if (frame != INVALID_FRAME) BuddyFrameAllocator.free(frame, 1uL)
            return
        }
        source.usePinned { data ->
            memcpy(destination, data.addressOf(offset), PAGE_SIZE_BYTES)
        }
        val published = lock.withLock {
            check(loads.remove(load))
            if (!load.valid || pages.containsKey(load.key)) return@withLock false
            UserFrameReferences.retain(frame)
            val page = CachedPage(load.key, frame, validBytes)
            pages[load.key] = page
            clock.addLast(page)
            true
        }
        if (!published) BuddyFrameAllocator.free(frame, 1uL)
    }

    private fun publish(candidate: CachedPage, load: PageLoad): CachedPage {
        val frame = candidate.frame
        UserFrameReferences.retain(frame)
        val acquired = lock.withLock {
            check(loads.remove(load))
            if (!load.valid) return@withLock candidate
            val existing = pages[candidate.key]
            if (existing != null) {
                existing.referenced = true
                UserFrameReferences.retain(existing.frame)
                existing
            } else {
                pages[candidate.key] = candidate
                clock.addLast(candidate)
                UserFrameReferences.retain(frame)
                candidate
            }
        }
        if (acquired !== candidate) UserFrameReferences.release(frame)
        return acquired
    }

    private fun cancel(load: PageLoad) {
        lock.withLock { check(loads.remove(load)) }
    }

    private fun PageCacheKey.intersects(
        identity: Any,
        start: ULong,
        end: ULong?,
    ): Boolean {
        if (this.identity != identity || end != null && offset >= end) return false
        return offset > ULong.MAX_VALUE - PAGE_SIZE_BYTES || start < offset + PAGE_SIZE_BYTES
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
