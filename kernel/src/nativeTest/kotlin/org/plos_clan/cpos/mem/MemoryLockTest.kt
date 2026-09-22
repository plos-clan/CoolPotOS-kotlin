package org.plos_clan.cpos.mem

import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryLock
import org.plos_clan.cpos.mem.addressspace.MemoryMapRequest
import org.plos_clan.cpos.mem.addressspace.MemoryMapResult
import org.plos_clan.cpos.mem.addressspace.MemoryRegionBacking
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.addressspace.PageFaultResult
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryLockTest {
    private class Source : MemoryRegionBacking() {
        var available = true
        var reads = 0
        var interrupted = false

        override fun read(offset: ULong, destination: ByteArray): Int {
            if (interrupted) return PageCacheSource.READ_INTERRUPTED
            if (!available) return -1
            reads++
            destination.fill(42)
            return destination.size
        }

        override fun acquirePage(offset: ULong, scratch: ByteArray): PageCacheAcquireResult =
            PageCache.acquire(this, offset, scratch)

        override fun close() = PageCache.invalidate(identity)
    }

    private class Fixture(private val source: Source? = null) : AutoCloseable {
        private val directory = KernelPageDirectory.getDirectory().createUserDirectory()
        val space = AddressSpace.user(directory)

        fun map(
            pages: ULong = 2uL,
            maximum: ULong = ULong.MAX_VALUE,
        ): MemoryMapResult<ULong> {
            val type = if (source == null) MemoryRegionType.ANONYMOUS else MemoryRegionType.FILE
            val request = MemoryMapRequest(
                hint = 0uL,
                length = pages * PAGE_SIZE_BYTES,
                access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                fixed = false,
                noReplace = false,
                shared = false,
                type = type,
                backing = source,
                lockedMemoryLimit = maximum,
            )
            return space.map(request)
        }

        override fun close() {
            space.release()
            source?.release()
        }
    }

    @Test
    fun interruptedPageReadsRemainRetryableWithoutBecomingIoErrors() {
        val source = Source()
        val fixture = Fixture(source)
        fixture.use {
            val mapping = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            val address = mapping.value
            val space = fixture.space
            source.interrupted = true
            assertEquals(PageFaultResult.INTERRUPTED, space.faultIn(address, false))
            assertNull(space.pageDirectory.userPageFrame(address))
            source.interrupted = false
            assertEquals(PageFaultResult.RESOLVED, space.faultIn(address, false))
            assertNotNull(space.pageDirectory.userPageFrame(address))
        }
    }

    @Test
    fun currentLockPrefaultsFilePagesBeforeBackingBecomesUnavailable() {
        val source = Source()
        val fixture = Fixture(source)
        fixture.use {
            val mapping = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            val address = mapping.value
            val space = fixture.space
            assertNull(space.pageDirectory.userPageFrame(address))
            val result = space.lockAllMemory(MemoryLock.EAGER, true, false, ULong.MAX_VALUE)
            assertEquals(0, result)
            val reads = source.reads
            assertTrue(reads > 0)
            source.available = false
            PageCache.reclaim(ULong.MAX_VALUE)
            val memory = UserMemory(space, address)
            val size = (PAGE_SIZE_BYTES * 2uL).toInt()
            val data = assertNotNull(memory.copyFromUser(size))
            assertTrue(data.all { it == 42.toByte() })
            assertEquals(reads, source.reads)
        }
    }

    @Test
    fun onFaultAndFuturePoliciesControlPopulationAndDoNotSurviveFork() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val maximum = ULong.MAX_VALUE
            assertEquals(0, space.lockAllMemory(MemoryLock.ON_FAULT, false, true, maximum))
            val mapping = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            val address = mapping.value
            val size = PAGE_SIZE_BYTES * 2uL
            assertEquals(size, space.lockedMemory)
            assertNull(space.pageDirectory.userPageFrame(address))
            assertEquals(0, space.lockAllMemory(MemoryLock.EAGER, true, false, maximum))
            assertNotNull(space.pageDirectory.userPageFrame(address))
            val child = space.fork()
            val inheritedLocks = child.lockedMemory
            child.release()
            assertEquals(0uL, inheritedLocks)
            val unlocked = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            assertNull(space.pageDirectory.userPageFrame(unlocked.value))
            assertEquals(size, space.lockedMemory)
            assertEquals(0, space.lockAllMemory(MemoryLock.EAGER, false, true, maximum))
            val eager = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            assertNotNull(space.pageDirectory.userPageFrame(eager.value))
            assertEquals(0, space.lockAllMemory(MemoryLock.NONE, true, false, 0uL))
            assertEquals(0uL, space.lockedMemory)
        }
    }

    @Test
    fun rangesRoundToPagesAccountOverlapsAndReleaseQuota() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val mapping = assertIs<MemoryMapResult.Ok<ULong>>(fixture.map())
            val address = mapping.value
            val page = PAGE_SIZE_BYTES
            val deferred = MemoryLock.ON_FAULT
            val eager = MemoryLock.EAGER
            assertEquals(0, space.lockMemory(address + 1uL, 1uL, deferred, page))
            assertEquals(0, space.lockMemory(address, 2uL, eager, page))
            assertEquals(page, space.lockedMemory)
            val second = address + page
            assertEquals(-Errno.ENOMEM, space.lockMemory(second, 1uL, eager, page))
            assertEquals(0, space.lockMemory(address, 1uL, MemoryLock.NONE, 0uL))
            assertEquals(0, space.lockMemory(second, 1uL, eager, page))
            assertIs<MemoryMapResult.Ok<Unit>>(space.unmap(second, page))
            assertEquals(0uL, space.lockedMemory)
            assertEquals(-Errno.ENOMEM, space.lockMemory(second, 1uL, deferred, page))
            assertEquals(-Errno.ENOMEM, space.lockMemory(ULong.MAX_VALUE, 2uL, eager, page))
            assertEquals(0, space.lockAllMemory(deferred, false, true, page))
            assertIs<MemoryMapResult.Err>(fixture.map(maximum = page))
        }
    }
}
