package org.plos_clan.cpos.mem

import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.ProcessArguments
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessArgumentsTest {
    private class Fixture : AutoCloseable {
        private val directory = KernelPageDirectory.getDirectory().createUserDirectory()
        val space = AddressSpace.user(directory)
        val start = USER_MMAP_START + PAGE_SIZE_BYTES - 4uL
        val memory = UserMemory(space, start)

        init {
            val region = MemoryRegion(
                start = USER_MMAP_START,
                end = USER_MMAP_START + PAGE_SIZE_BYTES * 3uL,
                access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                name = null,
            )
            assertTrue(space.insert(region))
            val initial = "worker\u0000xxxx\u0000KEY=value\u0000".encodeToByteArray()
            assertTrue(memory.copyToUser(initial))
            val argumentEnd = start + 12uL
            space.arguments = ProcessArguments(start, argumentEnd, argumentEnd, start + 22uL)
        }

        fun read(offset: Long = 0, count: Int = 4096): ByteArray =
            space.arguments.read(space, offset, count)

        override fun close() = space.release()
    }

    @Test
    fun readsCurrentArgumentsAcrossPagesAndHonorsOffsets() {
        val fixture = Fixture()
        fixture.use {
            val original = "worker\u0000xxxx\u0000".encodeToByteArray()
            val suffix = "ker\u0000".encodeToByteArray()
            val renamed = "@store".encodeToByteArray().copyOf(original.size)
            assertContentEquals(original, fixture.read())
            assertContentEquals(suffix, fixture.read(3, 4))
            assertTrue(fixture.memory.copyToUser(renamed))
            assertContentEquals(renamed, fixture.read())
            assertTrue(fixture.read(12).isEmpty())
            assertTrue(fixture.read(Long.MAX_VALUE).isEmpty())
            assertTrue(fixture.read(-1).isEmpty())
        }
    }

    @Test
    fun overwrittenTerminatorExtendsTitleIntoContiguousEnvironment() {
        val fixture = Fixture()
        fixture.use {
            val title = "worker: waiting\u0000".encodeToByteArray()
            val suffix = "waiting\u0000".encodeToByteArray()
            val truncated = "worker: wait".encodeToByteArray()
            assertTrue(fixture.memory.copyToUser(title))
            assertContentEquals(title, fixture.read())
            assertContentEquals(suffix, fixture.read(8))
            val arguments = fixture.space.arguments
            fixture.space.arguments = arguments.copy(environmentStart = fixture.start + 13uL)
            assertContentEquals(truncated, fixture.read())
        }
    }

    @Test
    fun forkKeepsIndependentArgumentMemoryAndMetadata() {
        val fixture = Fixture()
        val child = fixture.space.fork()
        try {
            val memory = UserMemory(child, fixture.start)
            val original = "worker\u0000xxxx\u0000".encodeToByteArray()
            val replacement = "child!\u0000xxxx\u0000".encodeToByteArray()
            val expected = "child!\u0000".encodeToByteArray()
            val end = fixture.start + expected.size.toULong()
            assertTrue(memory.copyToUser(replacement))
            assertEquals(0, child.relocateArguments(ProcessArguments.Boundary.END, end))
            assertContentEquals(expected, child.arguments.read(child, 0, 64))
            assertContentEquals(original, fixture.read())
            assertEquals(fixture.start + 12uL, fixture.space.arguments.end)
        } finally {
            child.release()
            fixture.close()
        }
    }

    @Test
    fun relocationRejectsKernelAddressesAndMissingMappingsReadAsEmpty() {
        val fixture = Fixture()
        fixture.use {
            val space = fixture.space
            val boundary = ProcessArguments.Boundary.START
            assertEquals(-Errno.EINVAL, space.relocateArguments(boundary, 0uL))
            val limit = USER_VIRTUAL_ADDRESS_LIMIT
            assertEquals(-Errno.EINVAL, space.relocateArguments(boundary, limit))
            val missing = USER_MMAP_START + PAGE_SIZE_BYTES * 4uL
            assertEquals(-Errno.EINVAL, space.relocateArguments(boundary, missing))
            val end = ProcessArguments.Boundary.END
            assertEquals(-Errno.EFAULT, space.relocateArguments(end, missing))
            space.unmap(USER_MMAP_START, PAGE_SIZE_BYTES * 3uL)
            assertTrue(fixture.read().isEmpty())
        }
    }
}
