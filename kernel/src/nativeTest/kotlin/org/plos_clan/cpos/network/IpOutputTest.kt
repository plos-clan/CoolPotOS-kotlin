package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.ProcessManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IpOutputTest {
    private class Fixture : AutoCloseable {
        val process = checkNotNull(ProcessManager.currentProcess())
        val network = NetworkStack()
        val address = Ipv4SocketAddress(Ipv4Address.fromBits(0x7f000001u), 0u)
        private val sockets = ArrayList<AbstractSocket>()

        init {
            val loopback = checkNotNull(network.interfaceByName("lo"))
            assertIs<VfsResult.Ok<Unit>>(network.setLink(loopback.index, true))
        }

        fun socket(icmp: Boolean = false): AbstractSocket {
            val socket = if (icmp) network.icmp.createSocket() else network.udp.createSocket()
            sockets.add(socket)
            assertIs<VfsResult.Ok<Unit>>(socket.bindSocket(process, address))
            return socket
        }

        fun send(socket: AbstractSocket, bytes: ByteArray, destination: Ipv4SocketAddress) {
            val buffer = ByteArrayBuffer(bytes)
            val source = checkNotNull(buffer.prepareRead(0, bytes.size))
            val request = SocketSendRequest(
                process, source, 0, bytes.size, destination = destination, nonBlocking = true,
            )
            assertEquals(bytes.size, socket.sendSocket(request).bytesTransferred)
        }

        fun receive(socket: AbstractSocket, count: Int): ByteArray {
            val bytes = ByteArray(count)
            val buffer = ByteArrayBuffer(bytes)
            val destination = checkNotNull(buffer.prepareWrite(0, count))
            val request = SocketReceiveRequest(destination, 0, count, nonBlocking = true)
            val result = socket.receiveSocket(request)
            val received = assertIs<VfsResult.Ok<SocketReceiveResult>>(result).value
            assertEquals(count, received.bytes)
            return bytes
        }

        override fun close() {
            sockets.forEach { it.release() }
        }
    }

    @Test
    fun udpPreservesEmptyOddAndMaximumDatagrams() {
        Fixture().use { fixture ->
            val sender = fixture.socket()
            val receiver = fixture.socket()
            val destination = assertIs<Ipv4SocketAddress>(receiver.localAddress())
            for (size in listOf(0, 1, 1025, 65_507)) {
                val bytes = ByteArray(size) { (it * 31).toByte() }
                fixture.send(sender, bytes, destination)
                assertContentEquals(bytes, fixture.receive(receiver, size))
            }
        }
    }

    @Test
    fun icmpEchoPreservesPayloadAndChecksum() {
        Fixture().use { fixture ->
            val socket = fixture.socket(icmp = true)
            val bytes = ByteArray(1033) { (it * 17).toByte() }
            bytes[0] = 8
            bytes[1] = 0
            fixture.send(socket, bytes, fixture.address)
            val reply = fixture.receive(socket, bytes.size)
            assertEquals(0, reply[0].toInt())
            assertTrue(InternetChecksum.valid(reply, 0, reply.size))
            assertContentEquals(bytes.copyOfRange(8, bytes.size), reply.copyOfRange(8, reply.size))
        }
    }

    @Test
    fun udpClosedPortDeliversQuotedIcmpError() {
        Fixture().use { fixture ->
            val socket = fixture.socket()
            val destination = fixture.address.copy(port = 54321u)
            assertIs<VfsResult.Ok<Unit>>(socket.connectSocket(fixture.process, destination, false))
            fixture.send(socket, byteArrayOf(1, 2, 3), destination)
            val bytes = ByteArray(8)
            val buffer = ByteArrayBuffer(bytes)
            val target = checkNotNull(buffer.prepareWrite(0, bytes.size))
            val request = SocketReceiveRequest(target, 0, bytes.size, nonBlocking = true)
            val result = assertIs<VfsResult.Err>(socket.receiveSocket(request))
            assertEquals(VfsError.CONNECTION_REFUSED, result.error)
        }
    }
}
