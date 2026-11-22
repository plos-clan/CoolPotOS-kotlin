@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.tmpfs.MemFd
import org.plos_clan.cpos.fs.tmpfs.MemFdFlags
import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.syscall.fs.FileIo
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_IO_VECTORS
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PositionedVectorIoTest {
    private class Fixture : AutoCloseable {
        val process = ProcessManager.createUserProcess("vector-io-test")
        val data = UserMemory(process.addressSpace, USER_MMAP_START + 128uL)
        val file: OpenFileDescription
        val descriptor: Int

        init {
            val access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE
            val region = MemoryRegion(USER_MMAP_START, USER_MMAP_START + PAGE_SIZE_BYTES, access, null)
            assertTrue(process.addressSpace.insert(region))
            val flags = assertIs<VfsResult.Ok<MemFdFlags>>(MemFdFlags.from(0u)).value
            val opened = MemFd.create(process.vfsOperationContext, byteArrayOf(), flags)
            file = assertIs<VfsResult.Ok<OpenFileDescription>>(opened).value
            descriptor = assertNotNull(process.fdTable.install(file, 0uL))
        }

        fun vectors(vararg segments: Pair<ULong, ULong>) {
            val bytes = ByteArray(segments.size * UserIoVector.NATIVE_SEGMENT_SIZE)
            val fields = LittleEndianBuffer(bytes)
            for ((index, segment) in segments.withIndex()) {
                val offset = index * UserIoVector.NATIVE_SEGMENT_SIZE
                fields.writeU64(offset, segment.first)
                fields.writeU64(offset + ULong.SIZE_BYTES, segment.second)
            }
            val table = UserMemory(process.addressSpace, USER_MMAP_START)
            assertTrue(table.copyToUser(bytes))
        }

        fun call(
            operation: FileIo,
            position: ULong,
            count: Int,
            address: ULong = USER_MMAP_START,
            fd: Int = descriptor,
        ): Long = memScoped {
            val frame = allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT)
            val regs = PtraceRegisters(frame)
            regs[PtraceRegisters.IDX_RDI] = fd.toULong()
            regs[PtraceRegisters.IDX_RSI] = address
            regs[PtraceRegisters.IDX_RDX] = count.toULong()
            regs[PtraceRegisters.IDX_R10] = position
            regs[PtraceRegisters.IDX_R8] = ULong.MAX_VALUE
            operation.vector(regs, process)
        }

        override fun close() {
            assertTrue(ProcessManager.discardUserProcess(process))
        }
    }

    @Test
    fun gathersAndScattersAtFullWidthOffsetWithoutMovingCursor() = Fixture().use { fixture ->
        val data = fixture.data
        val base = data.address
        val position = (1uL shl 32) + 9uL
        fixture.vectors(base + 4uL to 2uL, 0uL to 0uL, base to 3uL)
        assertTrue(data.copyToUser(byteArrayOf(10, 11, 12, 13, 14, 15)))
        assertEquals(5L, fixture.call(FileIo.PWRITE, position, 3))
        assertEquals(position + 5uL, fixture.file.inode.metadata().size)
        assertTrue(data.copyToUser(ByteArray(8)))
        fixture.vectors(base + 1uL to 3uL, base + 5uL to 3uL)
        assertEquals(5L, fixture.call(FileIo.PREAD, position, 2))
        assertContentEquals(byteArrayOf(0, 14, 15, 10, 0, 11, 12, 0), data.copyFromUser(8))
        assertEquals(0L, fixture.call(FileIo.PREAD, position + 5uL, 2))
        assertEquals(0L, fixture.file.offset)
    }

    @Test
    fun appendUsesEndOfFileAndPreservesSharedCursor() = Fixture().use { fixture ->
        fixture.vectors(fixture.data.address to 1uL)
        assertTrue(fixture.data.copyToUser(byteArrayOf(1)))
        assertEquals(1L, fixture.call(FileIo.WRITE, ULong.MAX_VALUE, 1))
        fixture.file.setStatusFlags(OpenFlags.O_APPEND)
        assertTrue(fixture.data.copyToUser(byteArrayOf(2)))
        assertEquals(1L, fixture.call(FileIo.PWRITE, 0uL, 1))
        fixture.vectors(fixture.data.address to 2uL)
        assertEquals(2L, fixture.call(FileIo.PREAD, 0uL, 1))
        assertContentEquals(byteArrayOf(1, 2), fixture.data.copyFromUser(2))
        assertEquals(1L, fixture.file.offset)
    }

    @Test
    fun validatesOffsetsDescriptorsAndVectors() = Fixture().use { fixture ->
        for (operation in listOf(FileIo.PREAD, FileIo.PWRITE)) {
            assertEquals(0L, fixture.call(operation, 0uL, 0, address = 0uL))
            assertEquals(Syscall.errno(Errno.EINVAL), fixture.call(operation, ULong.MAX_VALUE, 0))
            assertEquals(Syscall.errno(Errno.EBADF), fixture.call(operation, 0uL, 0, fd = -1))
            assertEquals(Syscall.errno(Errno.EINVAL), fixture.call(operation, 0uL, MAX_IO_VECTORS + 1))
            assertEquals(Syscall.errno(Errno.EFAULT), fixture.call(operation, 0uL, 1, address = 0uL))
            fixture.vectors(fixture.data.address to ULong.MAX_VALUE)
            assertEquals(Syscall.errno(Errno.EINVAL), fixture.call(operation, 0uL, 1))
            fixture.vectors(fixture.data.address to 1uL)
            assertEquals(Syscall.errno(Errno.EINVAL), fixture.call(operation, Long.MAX_VALUE.toULong(), 1))
        }
    }

    @Test
    fun pipesRejectOffsetsBeforeTouchingUserMemory() = Fixture().use { fixture ->
        val process = fixture.process
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val mount = vfs.createContext(Tmpfs.name)
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(mount).value
        val opened = try {
            vfs.createPipe(process.vfsOperationContext, context)
        } finally {
            context.release()
        }
        val ends = assertIs<VfsResult.Ok<Pair<OpenFileDescription, OpenFileDescription>>>(opened).value
        val descriptors = assertNotNull(process.fdTable.installAll(ends.toList(), 0uL))
        val expected = Syscall.errno(Errno.ESPIPE)
        assertEquals(expected, fixture.call(FileIo.PREAD, 0uL, 1, address = 0uL, fd = descriptors[0]))
        assertEquals(expected, fixture.call(FileIo.PWRITE, 0uL, 1, address = 0uL, fd = descriptors[1]))
    }
}
