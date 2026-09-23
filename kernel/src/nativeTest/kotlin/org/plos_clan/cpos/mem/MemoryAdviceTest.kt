@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.get

import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.CachedRegionBacking
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryLock
import org.plos_clan.cpos.mem.addressspace.MemoryMapRequest
import org.plos_clan.cpos.mem.addressspace.MemoryMapResult
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.mem.page.MMIO_PTE_FLAGS
import org.plos_clan.cpos.mem.page.PageTableLevel
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryAdviceTest {
    private class Source : CachedRegionBacking() {
        override fun read(offset: ULong, destination: ByteArray): Int {
            destination.fill((offset / PAGE_SIZE_BYTES + 23uL).toByte())
            return destination.size
        }

        override fun close() = PageCache.invalidate(identity)
    }

    private class Fixture(shared: Boolean = false, file: Boolean = false) : AutoCloseable {
        private val source = if (file) Source() else null
        val space = AddressSpace.user(KernelPageDirectory.getDirectory().createUserDirectory())
        val size = 3uL * PAGE_SIZE_BYTES
        val address: ULong
        val memory: UserMemory

        init {
            val request = MemoryMapRequest(
                hint = 0uL,
                length = size,
                access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                fixed = false,
                noReplace = false,
                shared = shared,
                type = if (file) MemoryRegionType.FILE else MemoryRegionType.ANONYMOUS,
                backing = source,
            )
            address = assertIs<MemoryMapResult.Ok<ULong>>(space.map(request)).value
            memory = UserMemory(space, address)
        }

        override fun close() {
            space.release()
            source?.release()
        }
    }

    @Test
    fun rangeRemovalTreatsPhysicalZeroAsALeafMapping() {
        val fixture = Fixture()
        fixture.use {
            val directory = fixture.space.pageDirectory
            val address = fixture.address
            assertTrue(directory.mapPage(address, 0uL, MMIO_PTE_FLAGS))
            directory.releasePages(address, address + PAGE_SIZE_BYTES, releaseFrames = false)
            val table = assertNotNull(directory.userPageTable(address))
            assertEquals(0uL, table[PageTableLevel.PT.index(address)])
        }
    }

    @Test
    fun privatePagesAreReleasedRoundedAndFaultedAsZeroWithoutChangingRegions() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val address = fixture.address
            val page = PAGE_SIZE_BYTES.toInt()
            assertEquals(page * 3, fixture.memory.fill(0, page * 3, 91))
            val regions = space.snapshotRegions()
            assertEquals(0, space.advise(address, PAGE_SIZE_BYTES + 1uL, 4))
            assertNull(space.pageDirectory.userPageFrame(address))
            assertNull(space.pageDirectory.userPageFrame(address + PAGE_SIZE_BYTES))
            assertNotNull(space.pageDirectory.userPageFrame(address + 2uL * PAGE_SIZE_BYTES))
            assertEquals(regions, space.snapshotRegions())
            val expected = ByteArray(page * 3)
            expected.fill(91, page * 2)
            assertContentEquals(expected, fixture.memory.copyFromUser(expected.size))
        }
    }

    @Test
    fun forkedPrivatePagesAndFileCopyOnWriteRetainTheirBacking() {
        for (file in listOf(false, true)) {
            val fixture = Fixture(file = file)
            fixture.use {
                assertEquals(1, fixture.memory.fill(0, 1, 91))
                val child = fixture.space.fork()
                try {
                    val childMemory = UserMemory(child, fixture.address)
                    assertEquals(0, fixture.space.advise(fixture.address, 1uL, 4))
                    assertContentEquals(byteArrayOf(91), childMemory.copyFromUser(1))
                    val original = if (file) 23.toByte() else 0.toByte()
                    assertContentEquals(byteArrayOf(original), fixture.memory.copyFromUser(1))
                } finally {
                    child.release()
                }
            }
        }
    }

    @Test
    fun sharedAnonymousPagesSurviveDiscardAndFaultAfterFork() {
        val fixture = Fixture(shared = true)
        fixture.use {
            val child = fixture.space.fork()
            try {
                val memory = UserMemory(child, fixture.address)
                assertEquals(1, fixture.memory.fill(0, 1, 91))
                assertContentEquals(byteArrayOf(91), memory.copyFromUser(1))
                assertEquals(0, child.advise(fixture.address, fixture.size, 4))
                assertEquals(0, fixture.space.advise(fixture.address, fixture.size, 4))
                assertEquals(1, memory.fill(0, 1, 42))
                assertContentEquals(byteArrayOf(42), fixture.memory.copyFromUser(1))
            } finally {
                child.release()
            }
        }
    }

    @Test
    fun holesReportEnomemAndStillDiscardMappedPagesOnBothSides() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val address = fixture.address
            val page = PAGE_SIZE_BYTES
            fixture.memory.fill(0, fixture.size.toInt(), 91)
            assertIs<MemoryMapResult.Ok<Unit>>(space.unmap(address + page, page))
            assertEquals(-Errno.ENOMEM, space.advise(address, fixture.size, 4))
            assertNull(space.pageDirectory.userPageFrame(address))
            assertNull(space.pageDirectory.userPageFrame(address + 2uL * page))
        }
    }

    @Test
    fun lockedPagesRejectDiscardAndInaccessiblePagesCanBeDiscarded() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val address = fixture.address
            val page = PAGE_SIZE_BYTES
            assertEquals(0, space.lockMemory(address, page, MemoryLock.ON_FAULT, page))
            assertEquals(-Errno.EINVAL, space.advise(address, page, 4))
            assertEquals(0, space.lockMemory(address, page, MemoryLock.NONE, page))
            fixture.memory.fill(0, 1, 91)
            assertIs<MemoryMapResult.Ok<Unit>>(space.protect(address, page, 0uL))
            assertEquals(0, space.advise(address, page, 4))
            assertNull(space.pageDirectory.userPageFrame(address))
            assertIs<MemoryMapResult.Ok<Unit>>(space.protect(address, page, MEMORY_REGION_READABLE))
            assertContentEquals(byteArrayOf(0), fixture.memory.copyFromUser(1))
        }
    }

    @Test
    fun invalidAdviceAlignmentAndOverflowAreRejectedIncludingZeroLength() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            assertEquals(-Errno.EINVAL, space.advise(fixture.address + 1uL, 0uL, 4))
            assertEquals(-Errno.EINVAL, space.advise(fixture.address, 0uL, -1))
            assertEquals(-Errno.EINVAL, space.advise(fixture.address, ULong.MAX_VALUE, 4))
            assertEquals(-Errno.EINVAL, space.advise(ULong.MAX_VALUE - 4095uL, 4096uL, 4))
            assertEquals(-Errno.ENOMEM, space.advise(0uL, 1uL, 4))
            assertEquals(0, space.advise(0uL, 0uL, 4))
            for (advice in 0..3) assertEquals(0, space.advise(fixture.address, 1uL, advice))
        }
    }

    @Test
    fun discardedPagesRemainPinnedOnlyForAnActiveCopy() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val memory = fixture.memory
            val prepared = assertNotNull(memory.prepareWrite(0, 1))
            val pinned = assertNotNull(space.acquireUserFrame(fixture.address, true))
            try {
                assertEquals(0, space.advise(fixture.address, 1uL, 4))
                assertEquals(1, prepared.fill(0, 1, 42))
                val replacement = space.pageDirectory.userPageFrame(fixture.address)
                assertNotEquals(pinned, replacement)
                assertContentEquals(byteArrayOf(42), memory.copyFromUser(1))
            } finally {
                assertTrue(UserFrameReferences.release(pinned))
            }
        }
    }
}
