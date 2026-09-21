package org.plos_clan.cpos.mem

import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
