package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.MappableFile
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.SeekOrigin
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DmaBufferTest {
    private class Fixture : AutoCloseable {
        val caller = VfsOperationContext.KERNEL
        val context: FileSystemContext
        val buffer: DmaBuffer

        init {
            val vfs = Vfs()
            assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
            val created = vfs.createContext(Tmpfs.name)
            context = assertIs<VfsResult.Ok<FileSystemContext>>(created).value
            val allocated = ResidentDmaBuffer.allocate(PAGE_SIZE_BYTES * 2uL)
            buffer = assertIs<VfsResult.Ok<ResidentDmaBuffer>>(allocated).value
        }

        fun export(access: AccessMode = AccessMode.READ_WRITE): OpenFileDescription {
            val result = buffer.export(caller, context, access)
            return assertIs<VfsResult.Ok<OpenFileDescription>>(result).value
        }

        override fun close() {
            buffer.release()
            context.release()
        }
    }

    @Test
    fun exportsShareIdentityAndAccessUntilTheLastFileReferenceCloses() {
        Fixture().use { fixture ->
            val first = fixture.export(AccessMode.READ)
            val second = fixture.export()
            assertSame(first, second)
            assertEquals(AccessMode.READ, second.access)
            first.release()
            second.release()
            assertFalse(first.retain())
            val replacement = fixture.export()
            assertNotSame(first, replacement)
            assertEquals(AccessMode.READ_WRITE, replacement.access)
            replacement.release()
        }
    }

    @Test
    fun mappingKeepsStorageAliveAndUsesTheBufferOffsetAsSharedIdentity() {
        val fixture = Fixture()
        val file = fixture.export()
        val provider = assertIs<MappableFile>(file.backend)
        val result = provider.map(file, true, 3uL, 3uL, PAGE_SIZE_BYTES, PAGE_SIZE_BYTES)
        val mapping = assertIs<VfsResult.Ok<MappedFile>>(result).value
        assertSame(fixture.buffer, mapping.sharedMemoryIdentity)
        assertEquals(PAGE_SIZE_BYTES, mapping.offset)
        file.release()
        fixture.close()
        val bytes = ByteArray(32) { 42 }
        assertEquals(bytes.size, mapping.read(PAGE_SIZE_BYTES, bytes))
        assertEquals(List(32) { 0.toByte() }, bytes.toList())
        mapping.release()
        assertFalse(file.retain())
        assertFalse(fixture.buffer.retain())
    }

    @Test
    fun sizeQueriesDoNotChangeTheFilePositionAndRejectOtherSeeks() {
        Fixture().use { fixture ->
            val file = fixture.export()
            val size = (PAGE_SIZE_BYTES * 2uL).toLong()
            val result = file.seek(fixture.caller, 0, SeekOrigin.END)
            assertEquals(size, assertIs<VfsResult.Ok<Long>>(result).value)
            assertEquals(0L, file.offset)
            val current = file.seek(fixture.caller, 0, SeekOrigin.CURRENT)
            assertEquals(VfsError.INVALID_ARGUMENT, assertIs<VfsResult.Err>(current).error)
            val nonzero = file.seek(fixture.caller, 1, SeekOrigin.START)
            assertEquals(VfsError.INVALID_ARGUMENT, assertIs<VfsResult.Err>(nonzero).error)
            file.release()
        }
    }

    @Test
    fun signalFdStillAcceptsNoopSeeksThroughTheBackendInterface() {
        Fixture().use { fixture ->
            val vfs = Vfs()
            val opened = vfs.createSignalFd(fixture.caller, fixture.context, 0uL, false)
            val file = assertIs<VfsResult.Ok<OpenFileDescription>>(opened).value
            for (origin in SeekOrigin.entries) {
                val result = file.seek(fixture.caller, 37, origin)
                assertEquals(0L, assertIs<VfsResult.Ok<Long>>(result).value)
            }
            file.release()
        }
    }
}
