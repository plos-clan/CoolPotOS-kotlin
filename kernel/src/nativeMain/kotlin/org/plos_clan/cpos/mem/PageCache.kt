@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.HashBuckets
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.isPageAligned
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

private sealed interface PageResult {
    class Failed(val reason: PageCacheFailure) : PageResult
}

private class CachedPage private constructor(
    val key: PageCacheKey,
    val frame: ULong,
    override val size: Int,
) : NativeMemorySource(), PageResult {
    private val pins = AtomicInt(1)
    @Volatile var referenced = true

    init {
        require(size in 0..PAGE_SIZE_BYTES.toInt())
        UserFrameReferences.retain(frame)
    }

    fun pin(): Boolean {
        var count = pins.load()
        while (count != 0) {
            val previous = pins.compareAndExchange(count, count + 1)
            if (previous == count) {
                if (!referenced) referenced = true
                return true
            }
            count = previous
        }
        return false
    }

    fun unpin() {
        if (pins.fetchAndAdd(-1) == 1) UserFrameReferences.release(frame)
    }

    val reclaimable get() = pins.load() == 1 && UserFrameReferences.isExclusive(frame)

    fun reclaim(): Boolean {
        if (!pins.compareAndSet(1, 0)) return false
        if (UserFrameReferences.releaseExclusive(frame)) return true
        pins.store(1)
        return false
    }

    override val pointer: CPointer<UByteVar>
        get() = checkNotNull(Hhdm.toVirtualPointer(frame))

    companion object {
        fun copyOf(key: PageCacheKey, bytes: CPointer<ByteVar>, valid: Int): PageResult {
            val frame = BuddyFrameAllocator.allocate(1uL)
            if (frame == INVALID_FRAME) {
                return PageResult.Failed(PageCacheFailure.OUT_OF_MEMORY)
            }
            val destination = Hhdm.toVirtualPointer<UByteVar>(frame)
            if (destination == null) {
                BuddyFrameAllocator.free(frame, 1uL)
                return PageResult.Failed(PageCacheFailure.IO_ERROR)
            }
            memcpy(destination, bytes, PAGE_SIZE_BYTES)
            return try {
                CachedPage(key, frame, valid)
            } catch (_: OutOfMemoryError) {
                BuddyFrameAllocator.free(frame, 1uL)
                PageResult.Failed(PageCacheFailure.OUT_OF_MEMORY)
            }
        }
    }
}

private class CachedSource {
    @Volatile var pages = persistentHashMapOf<PageCacheKey, CachedPage>()
    private val loads = HashMap<PageCacheKey, PageLoad>()
    private var readAheadEnd = 0uL
    private var readAheadPages = 0

    val idle get() = loads.isEmpty()

    private fun joinLoad(key: PageCacheKey): PageLoad {
        val load = loads.getOrPut(key) { PageLoad(key, this) }
        load.readers++
        return load
    }

    fun contains(load: PageLoad): Boolean = loads[load.key] === load

    fun finish(load: PageLoad) {
        if (!contains(load)) return
        load.readers--
        if (load.readers == 0) loads.remove(load.key)
    }

    fun reserve(key: PageCacheKey, maximumBytes: Int): List<PageLoad> {
        val pageSize = PAGE_SIZE_BYTES.toInt()
        val sequential = readAheadEnd != 0uL && key.offset == readAheadEnd
        val limit = (maximumBytes / pageSize).coerceAtLeast(1)
        val requested = if (sequential) minOf(2L * readAheadPages, limit.toLong()).toInt() else 1
        val batch = ArrayList<PageLoad>(1)
        batch += joinLoad(key)
        var offset = key.offset
        try {
            batch.ensureCapacity(requested)
            for (index in 1 until requested) {
                if (offset > ULong.MAX_VALUE - PAGE_SIZE_BYTES) break
                val next = key.copy(offset = offset + PAGE_SIZE_BYTES)
                if (next in pages || next in loads) break
                batch += joinLoad(next)
                offset = next.offset
            }
        } catch (_: OutOfMemoryError) {
            return batch
        }
        readAheadPages = batch.size
        readAheadEnd = offset + PAGE_SIZE_BYTES
        return batch
    }

    fun invalidate(identity: Any, offset: ULong, end: ULong?): List<CachedPage> {
        loads.keys.removeAll { it.intersects(offset, end) }
        val remaining = pages.builder()
        val retired = mutableListOf<CachedPage>()
        val first = offset.alignDown(PAGE_SIZE_BYTES)
        val pageCount = end?.let { (it - 1uL - first) / PAGE_SIZE_BYTES + 1uL }
        val kinds = PageCacheKind.entries
        val scanThreshold = pages.size.toULong() / kinds.size.toUInt()
        if (pageCount == null || pageCount >= scanThreshold) {
            for (page in pages.values) {
                if (!page.key.intersects(offset, end)) continue
                remaining.remove(page.key)
                retired += page
            }
        } else {
            var position = first
            repeat(pageCount.toInt()) {
                for (kind in kinds) {
                    val key = PageCacheKey(identity, position, kind)
                    val page = remaining.remove(key) ?: continue
                    retired += page
                }
                position += PAGE_SIZE_BYTES
            }
        }
        pages = remaining.build()
        return retired
    }
}

private class PageLoad(val key: PageCacheKey, val source: CachedSource) {
    var readers = 0
}

internal object PageCache : FrameReclaimer {
    private val shards = HashBuckets(SMProcessor.cpu_count.toInt(), ::PageCacheShard)

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
        val result = shards[key.identity].pin(source, key, scratch)
        return when (result) {
            is PageResult.Failed -> PageCacheAcquireResult.failed(result.reason)
            is CachedPage -> try {
                UserFrameReferences.retain(result.frame)
                PageCacheAcquireResult.acquired(result.frame, result.size)
            } finally {
                result.unpin()
            }
        }
    }

    fun contains(source: PageCacheSource, offset: ULong): Boolean {
        val key = PageCacheKey(source.identity, offset, source.cacheKind)
        return shards[key.identity].contains(key)
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
        val shard = shards[identity]
        while (copied < count) {
            val position = sourceOffset + copied.toULong()
            val pageOffset = position.alignDown(PAGE_SIZE_BYTES)
            val key = PageCacheKey(identity, pageOffset, source.cacheKind)
            val result = shard.pin(source, key, scratch) { scratch = it }
            val page = when (result) {
                is CachedPage -> result
                is PageResult.Failed -> {
                    if (copied == 0) return PageCacheReadResult.failed(result.reason)
                    break
                }
            }
            val pageIndex = (position - pageOffset).toInt()
            val chunk = minOf(count - copied, page.size - pageIndex)
            val transferred = try {
                if (chunk <= 0) break
                destination.copyFrom(destinationOffset + copied, page, pageIndex, chunk)
            } finally {
                page.unpin()
            }
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
        shards[identity].invalidate(identity, offset, end)
    }

    override fun reclaim(target: ULong): ULong {
        var reclaimed = 0uL
        for (shard in shards) {
            if (reclaimed == target) break
            reclaimed += shard.reclaim(target - reclaimed)
        }
        return reclaimed
    }

    fun statistics(): PageCacheStatistics {
        var files = 0uL
        var blocks = 0uL
        var reclaimable = 0uL
        for (shard in shards) {
            shard.forEachPage { page ->
                if (page.key.kind == PageCacheKind.BLOCK) blocks += PAGE_SIZE_BYTES
                else files += PAGE_SIZE_BYTES
                if (page.reclaimable) reclaimable += PAGE_SIZE_BYTES
            }
        }
        return PageCacheStatistics(files, reclaimable, blocks)
    }
}

private class PageCacheShard {
    private val lock = IrqSpinLock()
    @Volatile private var sources = persistentHashMapOf<Any, CachedSource>()
    private val clock = linkedSetOf<CachedPage>()

    fun contains(key: PageCacheKey) = sources[key.identity]?.pages?.containsKey(key) == true

    inline fun pin(
        source: PageCacheSource,
        key: PageCacheKey,
        scratch: ByteArray?,
        useBuffer: (ByteArray) -> Unit = {},
    ): PageResult {
        val cached = sources[key.identity]?.pages?.get(key)
        if (cached != null && cached.pin()) return cached
        val maximum = source.readAheadSize
        val batch = lock.withLock {
            val current = sources[key.identity] ?: CachedSource().also {
                sources = sources.putting(key.identity, it)
            }
            val page = current.pages[key]
            if (page != null) {
                check(page.pin())
                return page
            }
            current.reserve(key, maximum)
        }
        try {
            val pageSize = PAGE_SIZE_BYTES.toInt()
            val requested = batch.size * pageSize
            val buffer = if (scratch?.size == requested) scratch else try {
                ByteArray(requested)
            } catch (_: OutOfMemoryError) {
                scratch ?: ByteArray(pageSize)
            }
            useBuffer(buffer)
            return load(source, batch, buffer)
        } catch (_: OutOfMemoryError) {
            return PageResult.Failed(PageCacheFailure.OUT_OF_MEMORY)
        } finally {
            lock.withLock {
                val owner = batch.first().source
                batch.forEach(owner::finish)
                removeEmptySource(key.identity, owner)
            }
        }
    }

    fun invalidate(identity: Any, offset: ULong, end: ULong?) {
        val retired = lock.withLock {
            val source = sources[identity] ?: return
            val pages = source.invalidate(identity, offset, end)
            pages.forEach(clock::remove)
            removeEmptySource(identity, source)
            pages
        }
        retired.forEach(CachedPage::unpin)
    }

    inline fun forEachPage(visit: (CachedPage) -> Unit) {
        for (source in sources.values) source.pages.values.forEach(visit)
    }

    private fun load(
        source: PageCacheSource,
        batch: List<PageLoad>,
        buffer: ByteArray,
    ): PageResult {
        val load = batch.first()
        val pageSize = PAGE_SIZE_BYTES.toInt()
        buffer.fill(0)
        val count = try {
            source.read(load.key.offset, buffer)
        } catch (_: Throwable) {
            PageCacheSource.READ_ERROR
        }
        if (count !in 0..buffer.size) {
            val failure = if (count == PageCacheSource.READ_INTERRUPTED) {
                PageCacheFailure.INTERRUPTED
            } else {
                PageCacheFailure.IO_ERROR
            }
            return PageResult.Failed(failure)
        }
        return buffer.usePinned { data ->
            val primary = install(load, data.addressOf(0), minOf(count, pageSize))
            if (primary !is CachedPage) return@usePinned primary
            val pages = minOf(batch.size, buffer.size / pageSize)
            for (index in 1 until pages) {
                val offset = index * pageSize
                if (offset >= count) break
                val valid = minOf(count - offset, pageSize)
                val page = try {
                    install(batch[index], data.addressOf(offset), valid)
                } catch (_: OutOfMemoryError) {
                    break
                }
                if (page !is CachedPage) break
                page.unpin()
            }
            primary
        }
    }

    private fun install(load: PageLoad, bytes: CPointer<ByteVar>, valid: Int): PageResult {
        val key = load.key
        val candidate = CachedPage.copyOf(key, bytes, valid)
        if (candidate !is CachedPage) return candidate
        val existing = lock.withLock {
            val source = load.source
            if (!source.contains(load)) return candidate
            val page = source.pages[key]
            if (page != null) {
                check(page.pin())
                return@withLock page
            }
            try {
                val updated = source.pages.putting(key, candidate)
                clock.add(candidate)
                check(candidate.pin())
                source.pages = updated
            } catch (_: OutOfMemoryError) {
                return candidate
            }
            return candidate
        }
        candidate.unpin()
        return existing
    }

    private fun removeEmptySource(identity: Any, source: CachedSource) {
        if (!source.idle || source.pages.isNotEmpty()) return
        if (sources[identity] === source) sources = sources.removing(identity)
    }

    fun reclaim(target: ULong): ULong {
        var reclaimed = 0uL
        lock.tryWithLock {
            var remaining = clock.size.toLong() * 2
            while (remaining-- > 0 && reclaimed < target) {
                val iterator = clock.iterator()
                if (!iterator.hasNext()) break
                val page = iterator.next()
                val evicted = evict(page)
                iterator.remove()
                if (evicted) reclaimed++ else clock.add(page)
            }
        }
        return reclaimed
    }

    private fun evict(page: CachedPage): Boolean {
        if (page.referenced) {
            page.referenced = false
            return false
        }
        if (!page.reclaimable) return false
        val key = page.key
        val source = sources.getValue(key.identity)
        val remaining = source.pages.removing(key)
        val empty = remaining.isEmpty() && source.idle
        val directory = if (empty) sources.removing(key.identity) else sources
        if (!page.reclaim()) return false
        source.pages = remaining
        if (empty) sources = directory
        return true
    }
}
