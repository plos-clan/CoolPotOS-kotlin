package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AcceptedSocket
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.ProcessManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TcpStreamTest {
    private class Connection(receiveSize: Int) : AutoCloseable {
        val process = checkNotNull(ProcessManager.currentProcess())
        val listener = TcpProtocol.createSocket()
        val client = TcpProtocol.createSocket()
        val server: TcpSocket

        init {
            val address = Ipv4SocketAddress(Ipv4Address.fromBits(0x7f000001u), 0u)
            assertIs<VfsResult.Ok<Unit>>(listener.setReceiveBufferSize(receiveSize))
            assertIs<VfsResult.Ok<Unit>>(listener.bindSocket(process, address))
            assertIs<VfsResult.Ok<Unit>>(listener.listenSocket(process, 1))
            assertIs<VfsResult.Ok<Unit>>(client.connectSocket(process, listener.localAddress(), false))
            val accepted = listener.acceptSocket(process, true)
            val peer = assertIs<VfsResult.Ok<AcceptedSocket>>(accepted).value
            server = assertIs<TcpSocket>(peer.socket)
        }

        fun transfer() {
            val capacity = server.socketOptions().receiveBufferSize
            val input = ByteArray(capacity * 3) { (it * 31).toByte() }
            val output = ByteArray(input.size)
            val sourceBuffer = ByteArrayBuffer(input)
            val destinationBuffer = ByteArrayBuffer(output)
            val source = checkNotNull(sourceBuffer.prepareRead(0, input.size))
            val destination = checkNotNull(destinationBuffer.prepareWrite(0, output.size))
            assertIs<VfsResult.Ok<Unit>>(client.setSendBufferSize(input.size))
            val send = SocketSendRequest(process, source, 0, input.size, nonBlocking = true)
            assertEquals(input.size, client.sendSocket(send).bytesTransferred)
            assertTrue(client.outputQueueBytes() > 0)
            val peek = SocketReceiveRequest(destination, 0, 17, nonBlocking = true, peek = true)
            val peeked = server.receiveSocket(peek)
            assertEquals(17, assertIs<VfsResult.Ok<SocketReceiveResult>>(peeked).value.bytes)
            var offset = 0
            while (offset < output.size) {
                val count = minOf(127, output.size - offset)
                val request = SocketReceiveRequest(destination, offset, count, nonBlocking = true)
                val received = server.receiveSocket(request)
                val bytes = assertIs<VfsResult.Ok<SocketReceiveResult>>(received).value.bytes
                assertTrue(bytes > 0)
                offset += bytes
            }
            assertContentEquals(input, output)
            assertEquals(0, client.outputQueueBytes())
            assertIs<VfsResult.Ok<Unit>>(client.shutdownSocket(SocketShutdownMode.WRITE))
            val request = SocketReceiveRequest(destination, 0, 1, nonBlocking = true)
            val received = server.receiveSocket(request)
            assertEquals(0, assertIs<VfsResult.Ok<SocketReceiveResult>>(received).value.bytes)
        }

        override fun close() {
            client.release()
            server.release()
            listener.release()
        }
    }

    @Test
    fun reopensAnExhaustedWindowAfterPartialReads() {
        Connection(4096).use { it.transfer() }
    }

    @Test
    fun scaledWindowsPreserveDataAcrossSegmentsAndRingGrowth() {
        Connection(131072).use { it.transfer() }
    }
}
