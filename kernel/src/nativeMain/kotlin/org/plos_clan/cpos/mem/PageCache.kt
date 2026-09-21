@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.ExperimentalForeignApi
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

private data class PageCacheKey(
    val identity: Any,
    val offset: ULong,
    val kind: PageCacheKind,
) {
    fun intersects(start: ULong, end: ULong?): Boolean {
        if (end != null && offset >= end) return false
        return offset > ULong.MAX_VALUE - PAGE_SIZE_BYTES || start < offset + PAGE_SIZE_BYTES
    }
}

private class CachedPage(
    val key: PageCacheKey,
    val frame: ULong,
    val validBytes: Int,
    var referenced: Boolean = true,
) : NativeMemorySource() {
    override val pointer: kotlinx.cinterop.CPointer<UByteVar>
        get() = checkNotNull(Hhdm.toVirtualPointer(frame))

    override val size: Int
        get() = validBytes

    init {
        require(validBytes in 0..PAGE_SIZE_BYTES.toInt())
    }
}

private class CachedSource {
    val pages = mutableMapOf<PageCacheKey, CachedPage>()
    val loads = mutableSetOf<PageLoad>()
    var readAheadEnd = 0uL
    var readAheadPages = 0

    fun invalidate(identity: Any, offset: ULong, end: ULong?): List<CachedPage> {
        for (load in loads) {
            if (load.key.intersects(offset, end)) load.valid = false
        }
        val retired = mutableListOf<CachedPage>()
        val first = offset.alignDown(PAGE_SIZE_BYTES)
        val pageCount = end?.let { (it - 1uL - first) / PAGE_SIZE_BYTES + 1uL }
        val kinds = PageCacheKind.entries
        val scanThreshold = pages.size.toULong() / kinds.size.toUInt()
        if (pageCount == null || pageCount >= scanThreshold) {
            val iterator = pages.values.iterator()
            while (iterator.hasNext()) {
                val page = iterator.next()
                if (!page.key.intersects(offset, end)) continue
                retired += page
                iterator.remove()
            }
            return retired
        }
        var position = first
        repeat(pageCount.toInt()) {
            for (kind in kinds) {
                val key = PageCacheKey(identity, position, kind)
                val page = pages.remove(key) ?: continue
                retired += page
            }
            position += PAGE_SIZE_BYTES
        }
        return retired
    }
}

private class PageLoad(val key: PageCacheKey, val source: CachedSource) {
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
    private val sources = mutableMapOf<Any, CachedSource>()
    private val clock = linkedSetOf<CachedPage>()

    init {
        BuddyFrameAllocator.register(this)
    }

    fun acquire(
        source: PageCacheSource,
        offset: ULong,
        scratch: ByteArray,
    ): PageCacheAcquireResult {
        require(offset.isPageAligned() && scratch.size >= PAGE_SIZE_BYTES.toInt())
        val key = PageCacheKey(source.identity, offset, source.cacheKind)
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
            val key = PageCacheKey(identity, pageOffset, source.cacheKind)
            val page = when (val lookup = lookup(key)) {
                is PageLookup.Cached -> lookup.page
                is PageLookup.Missing -> {
                    val buffer = scratch ?: try {
                        ByteArray(PAGE_SIZE_BYTES.toInt()).also {
                            scratch = it
                        }
                    } catch (_: OutOfMemoryError) {
                        cancel(lookup.load)
                        return if (copied == 0) {
                            PageCacheReadResult.failed(PageCacheFailure.OUT_OF_MEMORY)
                        } else {
                            PageCacheReadResult.completed(copied)
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

            val pageIndex = (position - pageOffset).toInt()
            val available = page.validBytes - pageIndex
            if (available <= 0) {
                release(page.frame)
                break
            }
            val chunk = minOf(count - copied, available)
            val transferred = destination.copyFrom(
                destinationOffset + copied,
                page,
                pageIndex,
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
        val retired = lock.withLock {
            val source = sources[identity] ?: return
            val pages = source.invalidate(identity, offset, end)
            pages.forEach(clock::remove)
            removeEmptySource(identity, source)
            pages
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
        var bufferFrames = 0uL
        lock.withLock {
            cachedFrames = clock.size.toULong()
            bufferFrames = clock.count { it.key.kind == PageCacheKind.BLOCK }.toULong()
            reclaimableFrames = UserFrameReferences.countExclusive(clock) { it.frame }.toULong()
        }
        return PageCacheStatistics(
            cachedBytes = (cachedFrames - bufferFrames) * PAGE_SIZE_BYTES,
            reclaimableBytes = reclaimableFrames * PAGE_SIZE_BYTES,
            bufferBytes = bufferFrames * PAGE_SIZE_BYTES,
        )
    }

    private fun lookup(key: PageCacheKey): PageLookup = lock.withLock {
        val source = sources.getOrPut(key.identity, ::CachedSource)
        val page = source.pages[key]
        if (page != null) {
            page.referenced = true
            UserFrameReferences.retain(page.frame)
            return@withLock PageLookup.Cached(page)
        }
        val load = PageLoad(key, source)
        source.loads.add(load)
        PageLookup.Missing(load)
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

        var readAhead = reserveReadAhead(load, source.readAheadSize)
        val requestedSize = (readAhead.size + 1) * PAGE_SIZE_BYTES.toInt()
        val buffer = if (scratch.size == requestedSize) {
            scratch
        } else {
            try {
                ByteArray(requestedSize)
            } catch (_: OutOfMemoryError) {
                readAhead.forEach(::cancel)
                readAhead = emptyList()
                scratch
            }
        }
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

    private fun reserveReadAhead(load: PageLoad, maximumBytes: Int): List<PageLoad> = lock.withLock {
        val key = load.key
        val source = load.source
        val maximumPages = maxOf(1, maximumBytes / PAGE_SIZE_BYTES.toInt())
        val sequential = source.readAheadEnd != 0uL && key.offset == source.readAheadEnd
        val requestedPages = if (sequential) {
            minOf(source.readAheadPages.toLong() * 2, maximumPages.toLong()).toInt()
        } else 1
        val pending = ArrayList<PageLoad>(requestedPages - 1)
        var offset = key.offset
        for (index in 1 until requestedPages) {
            if (offset > ULong.MAX_VALUE - PAGE_SIZE_BYTES) break
            val nextKey = key.copy(offset = offset + PAGE_SIZE_BYTES)
            if (source.pages.containsKey(nextKey)) break
            if (source.loads.any { it.key == nextKey }) break
            offset = nextKey.offset
            val next = PageLoad(nextKey, source)
            source.loads.add(next)
            pending.add(next)
        }
        source.readAheadPages = pending.size + 1
        source.readAheadEnd = offset + PAGE_SIZE_BYTES
        pending
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
            val source = load.source
            check(source.loads.remove(load))
            if (!load.valid || source.pages.containsKey(load.key)) {
                removeEmptySource(load.key.identity, source)
                return@withLock false
            }
            UserFrameReferences.retain(frame)
            val page = CachedPage(load.key, frame, validBytes)
            source.pages[load.key] = page
            clock.add(page)
            true
        }
        if (!published) BuddyFrameAllocator.free(frame, 1uL)
    }

    private fun publish(candidate: CachedPage, load: PageLoad): CachedPage {
        val frame = candidate.frame
        UserFrameReferences.retain(frame)
        val acquired = lock.withLock {
            val source = load.source
            check(source.loads.remove(load))
            if (!load.valid) {
                removeEmptySource(load.key.identity, source)
                return@withLock candidate
            }
            val existing = source.pages[candidate.key]
            if (existing != null) {
                existing.referenced = true
                UserFrameReferences.retain(existing.frame)
                return@withLock existing
            }
            source.pages[candidate.key] = candidate
            clock.add(candidate)
            UserFrameReferences.retain(frame)
            candidate
        }
        if (acquired !== candidate) UserFrameReferences.release(frame)
        return acquired
    }

    private fun cancel(load: PageLoad) {
        lock.withLock {
            check(load.source.loads.remove(load))
            removeEmptySource(load.key.identity, load.source)
        }
    }

    private fun removeEmptySource(identity: Any, source: CachedSource) {
        if (source.pages.isEmpty() && source.loads.isEmpty()) sources.remove(identity)
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
            val iterator = clock.iterator()
            val page = iterator.next()
            iterator.remove()
            if (page.referenced) {
                page.referenced = false
                clock.add(page)
                continue
            }

            val release = UserFrameReferences.releaseExclusive(page.frame)
            if (release == FrameReleaseResult.RELEASED) {
                val source = sources.getValue(page.key.identity)
                check(source.pages.remove(page.key) === page)
                removeEmptySource(page.key.identity, source)
                return ClockScanResult.RECLAIMED
            }
            clock.add(page)
            if (release == FrameReleaseResult.CONTENDED) return ClockScanResult.CONTENDED
        }
        return ClockScanResult.UNAVAILABLE
    }
}
