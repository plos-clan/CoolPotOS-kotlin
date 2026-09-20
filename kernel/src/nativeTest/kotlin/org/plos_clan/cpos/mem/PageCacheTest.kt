package org.plos_clan.cpos.mem

import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageCacheTest {
    private class Source(override val identity: Any = Any()) : PageCacheSource {
        var reads = 0
        var value: Byte = 1
        var duringRead: (() -> Unit)? = null
        override val readAheadSize = 3 * PAGE_SIZE_BYTES.toInt()

        override fun read(offset: ULong, destination: ByteArray): Int {
            reads++
            destination.fill(value)
            duringRead?.invoke()
            return destination.size
        }

        fun byte(offset: ULong): Byte {
            val data = ByteArray(1)
            val destination = checkNotNull(ByteArrayBuffer(data).prepareWrite(0, 1))
            val result = PageCache.read(this, offset, destination, 0, 1)
            assertTrue(result.isSuccess)
            assertEquals(1, result.bytes)
            return data[0]
        }
    }

    @Test
    fun invalidationPreservesOtherSourcesAndNonOverlappingPages() {
        val source = Source()
        val other = Source()
        try {
            assertEquals(1.toByte(), source.byte(0uL))
            assertEquals(1.toByte(), source.byte(4uL * PAGE_SIZE_BYTES))
            assertEquals(1.toByte(), other.byte(0uL))
            source.value = 2
            PageCache.invalidate(source.identity, PAGE_SIZE_BYTES + 1uL, 1uL)
            assertEquals(1.toByte(), source.byte(0uL))
            assertEquals(1.toByte(), source.byte(2uL * PAGE_SIZE_BYTES))
            assertEquals(2.toByte(), source.byte(PAGE_SIZE_BYTES))
            assertEquals(1.toByte(), other.byte(0uL))
            assertEquals(3, source.reads)
            assertEquals(1, other.reads)
        } finally {
            PageCache.invalidate(source.identity)
            PageCache.invalidate(other.identity)
        }
    }

    @Test
    fun invalidationRejectsInFlightReadAheadAndAllowsReload() {
        val source = Source()
        try {
            source.duringRead = { PageCache.invalidate(source.identity) }
            assertEquals(1.toByte(), source.byte(0uL))
            source.duringRead = null
            source.value = 2
            assertEquals(2.toByte(), source.byte(PAGE_SIZE_BYTES))
            assertEquals(2.toByte(), source.byte(0uL))
            assertEquals(3, source.reads)
        } finally {
            PageCache.invalidate(source.identity)
        }
    }

    @Test
    fun reclamationKeepsAcquiredFramesAlive() {
        val source = Source()
        val scratch = ByteArray(PAGE_SIZE_BYTES.toInt())
        val first = PageCache.acquire(source, 0uL, scratch)
        assertTrue(first.isSuccess)
        try {
            PageCache.reclaim(ULong.MAX_VALUE)
            val second = PageCache.acquire(source, 0uL, scratch)
            assertTrue(second.isSuccess)
            try {
                assertEquals(first.frame, second.frame)
                assertEquals(1, source.reads)
            } finally {
                PageCache.release(second.frame)
            }
        } finally {
            PageCache.release(first.frame)
            PageCache.invalidate(source.identity)
        }
    }

    @Test
    fun readAheadStopsBeforeOffsetOverflow() {
        val source = Source()
        val lastPage = ULong.MAX_VALUE - PAGE_SIZE_BYTES + 1uL
        try {
            assertEquals(1.toByte(), source.byte(lastPage))
            source.value = 2
            assertEquals(1.toByte(), source.byte(lastPage))
            assertEquals(2.toByte(), source.byte(0uL))
            assertEquals(2, source.reads)
            PageCache.invalidate(source.identity, lastPage, PAGE_SIZE_BYTES)
            assertEquals(2.toByte(), source.byte(lastPage))
            assertEquals(3, source.reads)
        } finally {
            PageCache.invalidate(source.identity)
        }
    }

    @Test
    fun failedLoadsAndRepeatedInvalidationLeaveSourceReusable() {
        val cacheIdentity = Any()
        val failed = object : PageCacheSource {
            override val identity = cacheIdentity
            override fun read(offset: ULong, destination: ByteArray) = PageCacheSource.READ_ERROR
        }
        val source = Source(cacheIdentity)
        try {
            val result = PageCache.acquire(failed, 0uL, ByteArray(PAGE_SIZE_BYTES.toInt()))
            assertEquals(PageCacheFailure.IO_ERROR, result.failure)
            PageCache.invalidate(cacheIdentity)
            PageCache.invalidate(cacheIdentity, 0uL, 0uL)
            assertEquals(1.toByte(), source.byte(0uL))
            PageCache.invalidate(cacheIdentity)
            PageCache.invalidate(cacheIdentity)
            source.value = 2
            assertEquals(2.toByte(), source.byte(0uL))
        } finally {
            PageCache.invalidate(cacheIdentity)
        }
    }
}
