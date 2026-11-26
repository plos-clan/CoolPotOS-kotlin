package org.plos_clan.cpos.mem

import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuddyPoolTest {
    private val count = 64
    private val bytes = count.toULong() * PAGE_SIZE_BYTES
    private val range = MemoryRange(bytes, bytes)
    private var cpu = 0
    private val pool = BuddyPool(listOf(range), 2) { cpu }

    @Test
    fun coalescesPagesFreedOnDifferentCpus() {
        val pages = ULongArray(count) { pool.allocate(1uL) }
        assertEquals(count, pages.toSet().size)
        assertEquals(0uL, pool.statistics().freeBytes)
        for (index in pages.indices) {
            cpu = index % 2
            assertTrue(pool.free(pages[index], 1uL))
        }
        assertFalse(pool.free(pages.first(), 1uL))
        assertEquals(bytes, pool.statistics().freeBytes)
        assertEquals(range.base, pool.allocate(count.toULong()))
        assertFalse(pool.free(range.base, 1uL))
    }

    @Test
    fun splitAndCoalescedOrdersHaveDistinctOwnership() {
        val pages = ULongArray(count) { pool.allocate(1uL) }
        assertFalse(pool.free(range.base, 2uL))
        for (page in pages) assertTrue(pool.free(page, 1uL))
        val block = pool.allocate(3uL)
        assertNotEquals(INVALID_FRAME, block)
        assertFalse(pool.free(block + PAGE_SIZE_BYTES, 3uL))
        assertFalse(pool.free(block, 2uL))
        assertTrue(pool.free(block, 4uL))
        assertFalse(pool.free(block, 4uL))
        assertEquals(range.base, pool.allocate(count.toULong()))
    }

    @Test
    fun retriesContiguousAllocationAfterReclaimerReturnsCachedPages() {
        val pages = ULongArray(count) { pool.allocate(1uL) }
        assertNotEquals(INVALID_FRAME, pages.last())
        var calls = 0
        pool.register(object : FrameReclaimer {
            override fun reclaim(target: ULong): ULong {
                assertEquals(count.toULong(), target)
                cpu = 1
                calls++
                for (page in pages) assertTrue(pool.free(page, 1uL))
                return target
            }
        })
        assertEquals(range.base, pool.allocate(count.toULong()))
        assertEquals(1, calls)
        assertEquals(0uL, pool.statistics().freeBytes)
    }
}
