package org.plos_clan.cpos.mem.addressspace

import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryRegionMapTest {
    @Test
    fun insertsInAddressOrderAndRejectsInvalidRegions() {
        val map = regionMap()
        val later = region(4, 5)
        val earlier = region(1, 3)

        assertTrue(map.insertOwned(later))
        assertTrue(map.insertOwned(earlier))
        assertEquals(listOf(page(1), page(4)), map.map(MemoryRegion::start))
        assertEquals(3uL * PAGE_SIZE_BYTES, map.used)

        assertFalse(map.insertOwned(region(2, 4)))
        assertFalse(map.insertOwned(region(6, 7).copy(start = page(6) + 1uL)))
        assertFalse(
            map.insertOwned(
                region(
                    6,
                    7,
                    access = MEMORY_REGION_WRITABLE,
                    maximumAccess = MEMORY_REGION_READABLE,
                ),
            ),
        )
        assertFalse(map.insertOwned(region(31, 33)))
    }

    @Test
    fun treatsRegionEndsAsExclusive() {
        val map = regionMap()
        val first = region(2, 4)
        val second = region(4, 6)
        assertTrue(map.insertOwned(first))
        assertTrue(map.insertOwned(second))

        assertNull(map.find(page(2) - 1uL))
        assertSame(first, map.find(page(2)))
        assertSame(first, map.find(page(4) - 1uL))
        assertSame(second, map.find(page(4)))
        assertNull(map.find(page(6)))
        assertNull(map.intersection(page(4), page(4)))
        assertEquals(listOf(first, second), map.intersecting(page(3), page(5)))
        assertTrue(map.fullyCovers(page(2), page(6)))
        assertFalse(map.fullyCovers(page(2), page(7)))
    }

    @Test
    fun splitsAndRemovesRangesWhilePreservingFileOffsets() {
        val map = regionMap()
        val identity = Any()
        assertTrue(
            map.insertOwned(
                region(
                    2,
                    6,
                    type = MemoryRegionType.FILE,
                    offset = page(10),
                ).copy(identity = identity),
            ),
        )

        map.splitAt(page(4))
        assertEquals(listOf(page(2), page(4)), map.map(MemoryRegion::start))
        assertEquals(listOf(page(10), page(12)), map.map(MemoryRegion::offset))
        assertTrue(map.all { it.identity === identity })

        val removed = map.removeRange(page(3), page(5))
        assertEquals(listOf(page(3), page(4)), removed.map(MemoryRegion::start))
        assertEquals(listOf(page(11), page(12)), removed.map(MemoryRegion::offset))
        assertEquals(listOf(page(2), page(5)), map.map(MemoryRegion::start))
        assertEquals(listOf(page(10), page(13)), map.map(MemoryRegion::offset))
        assertEquals(2uL * PAGE_SIZE_BYTES, map.used)
    }

    @Test
    fun mergesOnlyCompatibleAdjacentRegions() {
        val map = regionMap()
        val identity = Any()
        val first = region(1, 2, type = MemoryRegionType.FILE, offset = 0uL)
            .copy(identity = identity)
        val second = region(2, 3, type = MemoryRegionType.FILE, offset = page(1))
            .copy(identity = identity)
        val incompatible = region(3, 4, type = MemoryRegionType.FILE, offset = 0uL)
        listOf(first, second, incompatible).forEach { assertTrue(map.insertOwned(it)) }

        map.mergeAround(second)

        assertEquals(2, map.count())
        assertEquals(page(1), first.start)
        assertEquals(page(3), first.end)
        assertSame(incompatible, map.find(page(3)))
    }

    @Test
    fun findsAlignedUnmappedAreas() {
        val map = MemoryRegionMap(
            allocationStart = page(1),
            allocationEnd = page(10),
            addressLimit = page(16),
        )
        assertTrue(map.insertOwned(region(3, 5)))

        assertEquals(page(2), map.findUnmappedArea(page(2) + 123uL, PAGE_SIZE_BYTES))
        assertEquals(page(9), map.findUnmappedArea(page(3), PAGE_SIZE_BYTES))
        assertNull(map.findUnmappedArea(page(1), page(10)))
    }

    private fun regionMap(): MemoryRegionMap = MemoryRegionMap(
        allocationStart = page(1),
        allocationEnd = page(16),
        addressLimit = page(32),
    )

    private fun region(
        startPage: Int,
        endPage: Int,
        access: ULong = MEMORY_REGION_READABLE,
        maximumAccess: ULong = MEMORY_REGION_ACCESS_MASK,
        type: MemoryRegionType = MemoryRegionType.ANONYMOUS,
        offset: ULong = 0uL,
    ): MemoryRegion = MemoryRegion(
        start = page(startPage),
        end = page(endPage),
        access = access,
        maximumAccess = maximumAccess,
        name = "test",
        type = type,
        offset = offset,
    )

    private fun page(index: Int): ULong = index.toULong() * PAGE_SIZE_BYTES
}
