package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.tmpfs.MemFd
import org.plos_clan.cpos.fs.tmpfs.MemFdFlags
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SendFileTest {
    private val caller = VfsOperationContext.KERNEL

    private fun open(): OpenFileDescription {
        val flags = assertIs<VfsResult.Ok<MemFdFlags>>(MemFdFlags.from(0u)).value
        val result = MemFd.create(caller, byteArrayOf(), flags)
        return assertIs<VfsResult.Ok<OpenFileDescription>>(result).value
    }

    @Test
    fun explicitAndSharedOffsetsAdvanceByAcceptedBytes() {
        val source = open()
        val target = open()
        val data = ByteArray(4097) { it.toByte() }
        try {
            val input = ByteArrayBuffer(data)
            val written = source.writeAt(caller, 0u, input, 0, data.size)
            assertEquals(data.size, written.bytesTransferred)
            assertEquals(VfsResult.Ok(7uL), source.sendFile(caller, target, 4090u, 100))
            assertEquals(0L, source.offset)
            assertEquals(VfsResult.Ok(9uL), source.sendFile(caller, target, null, 9))
            assertEquals(9L, source.offset)
            assertEquals(16L, target.offset)
            val received = ByteArray(16)
            val output = ByteArrayBuffer(received)
            assertEquals(16, target.readAt(caller, 0u, output, 0, 16).bytesTransferred)
            assertContentEquals(data.copyOfRange(4090, 4097) + data.copyOf(9), received)
            assertEquals(VfsResult.Ok(0uL), source.sendFile(caller, target, 4097u, 12))
            target.setStatusFlags(OpenFlags.O_APPEND)
            val error = source.sendFile(caller, target, null, 12)
            assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), error)
            assertEquals(9L, source.offset)
        } finally {
            source.release()
            target.release()
        }
    }
}
