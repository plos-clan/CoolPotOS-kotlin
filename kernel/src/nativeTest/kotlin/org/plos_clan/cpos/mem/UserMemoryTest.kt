package org.plos_clan.cpos.mem

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.MemoryMapResult
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.Errno
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserMemoryTest {
    private class Fixture(writable: Boolean = true) : AutoCloseable {
        private val directory = KernelPageDirectory.getDirectory().createUserDirectory()
        val space = AddressSpace.user(directory)
        val address = USER_MMAP_START + PAGE_SIZE_BYTES - 17uL
        val memory = UserMemory(space, address)

        init {
            val access = MEMORY_REGION_READABLE or if (writable) MEMORY_REGION_WRITABLE else 0uL
            val region = MemoryRegion(
                start = USER_MMAP_START,
                end = USER_MMAP_START + 3uL * PAGE_SIZE_BYTES,
                access = access,
                name = null,
            )
            assertTrue(space.insert(region))
        }

        override fun close() = space.release()
    }

    @Test
    fun residencyDoesNotFaultPagesAndRetainsInaccessiblePages() {
        Fixture().use { fixture ->
            val vector = ByteArray(3) { 7 }
            val start = USER_MMAP_START
            assertEquals(0, fixture.space.residency(start, vector, 3))
            assertContentEquals(byteArrayOf(0, 0, 0), vector)
            val memory = UserMemory(fixture.space, start + PAGE_SIZE_BYTES)
            assertTrue(memory.copyToUser(byteArrayOf(42)))
            assertEquals(0, fixture.space.residency(start, vector, 3))
            assertContentEquals(byteArrayOf(0, 1, 0), vector)
            val protection = fixture.space.protect(start, 3uL * PAGE_SIZE_BYTES, 0uL)
            assertIs<MemoryMapResult.Ok<Unit>>(protection)
            assertEquals(0, fixture.space.residency(start, vector, 3))
            assertContentEquals(byteArrayOf(0, 1, 0), vector)
            val unmapped = start + 3uL * PAGE_SIZE_BYTES
            assertEquals(-Errno.ENOMEM, fixture.space.residency(unmapped, vector, 1))
        }
    }

    @Test
    fun stringsTerminateAtPageBoundariesAndRespectLimits() {
        Fixture().use { fixture ->
            for (length in listOf(0, 7, 16, 17, 18, PAGE_SIZE_BYTES.toInt() + 25)) {
                val bytes = ByteArray(length + 1) { 65 }
                bytes[length] = 0
                assertTrue(fixture.memory.copyToUser(bytes))
                val result = fixture.memory.copyCStringFromUser(bytes.size)
                val copied = assertIs<VfsResult.Ok<ByteArray>>(result).value
                assertContentEquals(bytes.copyOf(length), copied)
                if (length == 0) continue
                val limited = fixture.memory.copyCStringFromUser(length, VfsError.RANGE)
                assertEquals(VfsError.RANGE, assertIs<VfsResult.Err>(limited).error)
            }
            val last = USER_MMAP_START + 3uL * PAGE_SIZE_BYTES - 1uL
            val memory = UserMemory(fixture.space, last)
            assertTrue(memory.copyToUser(byteArrayOf(0)))
            val empty = assertIs<VfsResult.Ok<ByteArray>>(memory.copyCStringFromUser(2))
            assertContentEquals(ByteArray(0), empty.value)
            assertTrue(memory.copyToUser(byteArrayOf(65)))
            val fault = assertIs<VfsResult.Err>(memory.copyCStringFromUser(2))
            assertEquals(VfsError.FAULT, fault.error)
        }
    }

    @Test
    fun copiesAndFillsAcrossFaultedPagesInAnotherAddressSpace() {
        Fixture().use { fixture ->
            val bytes = ByteArray(PAGE_SIZE_BYTES.toInt() + 31) { (it * 37).toByte() }
            val memory = fixture.memory
            val destination = assertNotNull(memory.prepareWrite(0, bytes.size))
            assertEquals(bytes.size, destination.copyFrom(0, bytes, 0, bytes.size))
            assertContentEquals(bytes, memory.copyFromUser(bytes.size))
            assertEquals(bytes.size - 2, destination.fill(1, bytes.size - 2, 93))
            bytes.fill(93, 1, bytes.size - 1)
            assertContentEquals(bytes, memory.copyFromUser(bytes.size))
        }
    }

    @Test
    fun nestedUserCopiesWithinAnAddressSpaceDoNotHoldItsLockTwice() {
        Fixture().use { fixture ->
            val bytes = ByteArray(64) { it.toByte() }
            assertTrue(fixture.memory.copyToUser(bytes))
            val address = USER_MMAP_START + PAGE_SIZE_BYTES * 2uL
            val destination = UserMemory(fixture.space, address)
            assertEquals(bytes.size, destination.copyFrom(0, fixture.memory, 0, bytes.size))
            assertContentEquals(bytes, destination.copyFromUser(bytes.size))
        }
    }

    @Test
    fun rejectsUnmappedAndReadOnlyDestinations() {
        Fixture(writable = false).use { fixture ->
            val bytes = ByteArray(64)
            assertContentEquals(bytes, fixture.memory.copyFromUser(bytes.size))
            assertNull(fixture.memory.prepareWrite(0, bytes.size))
            assertEquals(0, fixture.memory.fill(0, bytes.size, 1))
            val unmapped = UserMemory(fixture.space, USER_MMAP_START - PAGE_SIZE_BYTES)
            assertNull(unmapped.prepareRead(0, bytes.size))
            val boundary = UserMemory(fixture.space, USER_VIRTUAL_ADDRESS_LIMIT - 1uL)
            assertNull(boundary.prepareRead(0, 2))
        }
    }

    @Test
    fun deferredPreparationChecksAddressesWithoutFaultingUnusedPages() {
        Fixture(writable = false).use { fixture ->
            val policy = BufferFaultPolicy.ON_ACCESS
            val size = 3 * PAGE_SIZE_BYTES.toInt()
            assertNotNull(fixture.memory.prepareWrite(0, size, policy))
            assertNull(fixture.space.pageDirectory.userPageFrame(USER_MMAP_START))
            assertNull(fixture.memory.prepareWrite(-1, size, policy))
            assertNull(fixture.memory.prepareWrite(0, -1, policy))
            val boundary = UserMemory(fixture.space, USER_VIRTUAL_ADDRESS_LIMIT - 1uL)
            assertNotNull(boundary.prepareWrite(0, 1, policy))
            assertNull(boundary.prepareWrite(0, 2, policy))
            val kernel = UserMemory(fixture.space, USER_VIRTUAL_ADDRESS_LIMIT)
            assertNull(kernel.prepareWrite(0, 1, policy))
        }
    }

    @Test
    fun copyingIntoForkedPagesPreservesTheParent() {
        Fixture().use { fixture ->
            val original = ByteArray(64) { it.toByte() }
            assertTrue(fixture.memory.copyToUser(original))
            val child = fixture.space.fork()
            try {
                val memory = UserMemory(child, fixture.address)
                val expected = ByteArray(original.size) { 42 }
                assertContentEquals(original, memory.copyFromUser(original.size))
                assertEquals(original.size, memory.fill(0, original.size, 42))
                assertContentEquals(expected, memory.copyFromUser(original.size))
                assertContentEquals(original, fixture.memory.copyFromUser(original.size))
            } finally {
                child.release()
            }
        }
    }
}
