package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.ClassicBpfProgram
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.sock.UnixCredentials
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetlinkFilterTest {
    @Test
    fun filtersBeforeQueueAccountingAndPreservesSender() {
        val network = NetworkStack()
        val protocol = network.netlink.uevent
        val socket = protocol.createSocket(SocketType.RAW)
        val bytes = ByteArray(32)
        val destination = checkNotNull(ByteArrayBuffer(bytes).prepareWrite(0, bytes.size))
        val request = SocketReceiveRequest(destination, 0, bytes.size, nonBlocking = true)
        val credentials = UnixCredentials(42, 1000u, 1001u)
        try {
            assertIs<VfsResult.Ok<Unit>>(socket.setReceiveBufferSize(0))
            assertIs<VfsResult.Ok<Unit>>(socket.attachFilter(filter(0u)))
            assertTrue(socket.enqueue(ByteArray(8192)))
            assertEquals(VfsError.WOULD_BLOCK, assertIs<VfsResult.Err>(socket.receiveSocket(request)).error)
            assertEquals(null, socket.takeError())

            assertIs<VfsResult.Ok<Unit>>(socket.attachFilter(filter(3u)))
            assertTrue(socket.enqueue(byteArrayOf(1, 2, 3, 4), 42u, 2, credentials))
            val received = assertIs<VfsResult.Ok<SocketReceiveResult>>(socket.receiveSocket(request)).value
            assertEquals(3, received.bytes)
            assertEquals(NetlinkSocketAddress(42u, 2u), received.source)
            assertEquals(credentials, received.senderCredentials)
            assertContentEquals(byteArrayOf(1, 2, 3), bytes.copyOf(3))
            assertEquals(VfsError.WOULD_BLOCK, assertIs<VfsResult.Err>(socket.receiveSocket(request)).error)
        } finally {
            socket.release()
        }
    }

    @Test
    fun replacementDoesNotRefilterQueuedMessages() {
        val network = NetworkStack()
        val protocol = RouteNetlinkProtocol(network)
        val socket = protocol.createSocket(SocketType.DATAGRAM)
        val bytes = ByteArray(4)
        val destination = checkNotNull(ByteArrayBuffer(bytes).prepareWrite(0, bytes.size))
        val request = SocketReceiveRequest(destination, 0, bytes.size, nonBlocking = true)
        try {
            assertIs<VfsResult.Ok<Unit>>(socket.attachFilter(filter(UInt.MAX_VALUE)))
            assertTrue(socket.enqueue(byteArrayOf(1, 2, 3, 4)))
            assertIs<VfsResult.Ok<Unit>>(socket.attachFilter(filter(0u)))
            assertTrue(socket.enqueue(byteArrayOf(5, 6)))
            assertIs<VfsResult.Ok<*>>(socket.receiveSocket(request))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
            assertEquals(VfsError.WOULD_BLOCK, assertIs<VfsResult.Err>(socket.receiveSocket(request)).error)
        } finally {
            socket.release()
        }
    }

    private fun filter(length: UInt): ClassicBpfProgram {
        val bytes = ByteArray(ClassicBpfProgram.INSTRUCTION_SIZE)
        LittleEndianBuffer(bytes).apply {
            writeU16(0, 0x06u)
            writeU32(4, length)
        }
        return assertIs<VfsResult.Ok<ClassicBpfProgram>>(ClassicBpfProgram.decode(bytes)).value
    }
}
