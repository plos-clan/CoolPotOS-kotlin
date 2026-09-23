package org.plos_clan.cpos.fs.erofs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ExtentCacheTest {
    @Test
    fun cacheHitsProtectHotExtentsFromEviction() {
        val cache = ExtentCache<ULong>(8)
        val first = ByteArray(4)
        val second = ByteArray(4)
        assertSame(first, cache.getOrLoad(1uL) { first })
        assertSame(second, cache.getOrLoad(2uL) { second })
        assertSame(first, cache.getOrLoad(1uL) { error("Cached extent was reloaded") })
        cache.getOrLoad(3uL) { ByteArray(4) }
        assertSame(first, cache.getOrLoad(1uL) { error("Hot extent was evicted") })
        var loads = 0
        cache.getOrLoad(2uL) { loads++; second }
        assertEquals(1, loads)
    }

    @Test
    fun failuresAndOversizedExtentsDoNotPoisonTheCache() {
        val cache = ExtentCache<ULong>(4)
        assertNull(cache.getOrLoad(1uL) { null })
        assertFailsWith<IllegalStateException> {
            cache.getOrLoad(1uL) { error("Read failed") }
        }
        val oversized = ByteArray(8)
        assertSame(oversized, cache.getOrLoad(1uL) { oversized })
        val fitting = ByteArray(4)
        assertSame(fitting, cache.getOrLoad(1uL) { fitting })
        assertSame(fitting, cache.getOrLoad(1uL) { error("Cached extent was reloaded") })
    }
}
