package org.plos_clan.cpos.fs.fuse

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.DeviceNode
import org.plos_clan.cpos.fs.DeviceOpenFile
import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuseReadTest {
    private class Fixture(writable: Boolean = true) : AutoCloseable, FuseNotificationSink {
        val caller = VfsOperationContext.KERNEL
        val session = FuseSession()
        private val directory = KernelPageDirectory.getDirectory().createUserDirectory()
        val space = AddressSpace.user(directory)
        val memory = UserMemory(space, USER_MMAP_START)
        private val context: FileSystemContext
        val file: OpenFileDescription

        init {
            val vfs = Vfs()
            assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
            context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
            val access = MEMORY_REGION_READABLE or if (writable) MEMORY_REGION_WRITABLE else 0uL
            val region = MemoryRegion(
                start = USER_MMAP_START,
                end = USER_MMAP_START + 2uL * PAGE_SIZE_BYTES,
                access = access,
                name = null,
            )
            assertTrue(space.insert(region))
            val number = checkNotNull(DeviceNumber.create(10u, 229u))
            val backend = DeviceNode(InodeType.CHARACTER_DEVICE, number.value)
            val metadata = checkNotNull(context.root.dentry.inode()).metadata()
            val inode = AnonymousFileFactory().createInode(context, backend, metadata)
            val device = Device("fuse-test", DeviceType.CHARACTER, number, session)
            val options = OpenOptions(access = AccessMode.READ, nonBlocking = true)
            val opened = DeviceOpenFile.open(device, session)
            val result = OpenFileDescription.open(
                caller, context.root, inode, options, openedBackend = opened,
            )
            file = assertIs<VfsResult.Ok<OpenFileDescription>>(result).value
            assertIs<VfsResult.Ok<Unit>>(session.attach(FuseAbi.MAX_TRANSFER_SIZE, this))
        }

        fun read(memory: UserMemory = this.memory, count: Int = FuseAbi.MAX_PACKET_SIZE) =
            file.read(caller, memory, 0, count)

        override fun wakePoll(handle: ULong) {}
        override fun invalidateInode(nodeId: ULong, offset: Long, length: Long) {}
        override fun invalidateEntry(parentId: ULong, name: VfsName, childId: ULong?) {}

        override fun close() {
            file.release()
            context.release()
            space.release()
        }
    }

    @Test
    fun largeReceiveBufferFaultsOnlyPagesContainingTheMessage() {
        Fixture().use { fixture ->
            val result = fixture.read()
            val size = FuseAbi.IN_HEADER_SIZE + 64
            assertTrue(result.isSuccess)
            assertEquals(size, result.bytesTransferred)
            val bytes = assertNotNull(fixture.memory.copyFromUser(size))
            val header = LittleEndianBuffer(bytes)
            assertEquals(size.toUInt(), header.readU32(0))
            assertEquals(FuseOpcode.INIT.value, header.readU32(4))
            assertNotNull(fixture.space.pageDirectory.userPageFrame(USER_MMAP_START))
            val nextPage = USER_MMAP_START + PAGE_SIZE_BYTES
            assertNull(fixture.space.pageDirectory.userPageFrame(nextPage))
        }
    }

    @Test
    fun failedCopyRestoresTheRequestForAnotherReader() {
        Fixture().use { fixture ->
            val end = USER_MMAP_START + 2uL * PAGE_SIZE_BYTES
            val partial = UserMemory(fixture.space, end - 17uL)
            assertEquals(VfsError.FAULT, fixture.read(partial).error)
            val valid = UserMemory(fixture.space, USER_MMAP_START + PAGE_SIZE_BYTES - 17uL)
            val result = fixture.read(valid)
            assertTrue(result.isSuccess)
            assertEquals(FuseAbi.IN_HEADER_SIZE + 64, result.bytesTransferred)
            assertEquals(VfsError.WOULD_BLOCK, fixture.read().error)
        }
    }

    @Test
    fun readOnlyBufferFailsAtCopyAndLeavesTheRequestQueued() {
        Fixture(writable = false).use { fixture ->
            assertEquals(VfsError.FAULT, fixture.read().error)
            assertEquals(VfsError.FAULT, fixture.read().error)
            val page = fixture.space.pageDirectory.userPageFrame(USER_MMAP_START)
            assertNull(page)
        }
    }

    @Test
    fun vectoredReadDoesNotTouchUnusedSegments() {
        Fixture().use { fixture ->
            val descriptors = ByteArray(2 * UserIoVector.NATIVE_SEGMENT_SIZE)
            val fields = LittleEndianBuffer(descriptors)
            val destination = USER_MMAP_START + 256uL
            val size = FuseAbi.IN_HEADER_SIZE + 64
            fields.writeU64(0, destination)
            fields.writeU64(8, size.toULong())
            fields.writeU64(16, USER_VIRTUAL_ADDRESS_LIMIT)
            fields.writeU64(24, FuseAbi.MAX_PACKET_SIZE.toULong())
            assertTrue(fixture.memory.copyToUser(descriptors))
            val invalid = assertNotNull(UserIoVector.fromUser(
                fixture.space, USER_MMAP_START, 2, Int.MAX_VALUE,
            ))
            val failed = fixture.file.read(fixture.caller, invalid, 0, invalid.size)
            assertEquals(VfsError.FAULT, failed.error)
            fields.writeU64(16, USER_MMAP_START - PAGE_SIZE_BYTES)
            assertTrue(fixture.memory.copyToUser(descriptors))
            val vector = assertNotNull(UserIoVector.fromUser(
                fixture.space, USER_MMAP_START, 2, Int.MAX_VALUE,
            ))
            val result = fixture.file.read(fixture.caller, vector, 0, vector.size)
            assertTrue(result.isSuccess)
            assertEquals(size, result.bytesTransferred)
            val memory = UserMemory(fixture.space, destination)
            assertEquals(size.toUInt(), memory.readUIntLE())
        }
    }

    @Test
    fun receiveIntoForkedMemoryPreservesTheParent() {
        Fixture().use { fixture ->
            val size = FuseAbi.IN_HEADER_SIZE + 64
            assertTrue(fixture.memory.copyToUser(ByteArray(size)))
            val child = fixture.space.fork()
            try {
                val memory = UserMemory(child, USER_MMAP_START)
                assertTrue(fixture.read(memory).isSuccess)
                assertEquals(size.toUInt(), memory.readUIntLE())
                assertEquals(0u, fixture.memory.readUIntLE())
            } finally {
                child.release()
            }
        }
    }

    @Test
    fun emptyQueueAndSmallBuffersDoNotAccessUserMemory() {
        Fixture().use { fixture ->
            val unmapped = UserMemory(fixture.space, USER_MMAP_START - PAGE_SIZE_BYTES)
            assertEquals(VfsError.INVALID_ARGUMENT, fixture.read(unmapped, 1).error)
            assertTrue(fixture.read().isSuccess)
            assertEquals(VfsError.WOULD_BLOCK, fixture.read(unmapped).error)
        }
    }
}
