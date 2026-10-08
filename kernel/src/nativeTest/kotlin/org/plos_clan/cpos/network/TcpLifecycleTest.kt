package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.sock.AcceptedSocket
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.ProcessManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TcpLifecycleTest {
    private class Retained : AutoCloseable {
        val network = NetworkStack()
        val protocol = network.tcp
        val owner = protocol.createSocket()
        val replacement = protocol.createSocket()
        val local = Ipv4SocketAddress(Ipv4Address.fromBits(0xc0000201u), 8080u)
        val remote = Ipv4SocketAddress(Ipv4Address.fromBits(0xc0000202u), 40000u)

        init {
            assertIs<VfsResult.Ok<Ipv4SocketAddress>>(protocol.bind(owner, local, false))
            assertIs<VfsResult.Ok<Unit>>(protocol.registerConnection(owner, local, remote))
            val acknowledgment = TcpTransmission(local, remote, 100u, 200u, TcpFlags.ACK, 0u)
            protocol.timeWait(owner, acknowledgment)
        }

        fun send(sequence: UInt, flags: Int) {
            val bytes = ByteArray(TcpCodec.MIN_HEADER_SIZE)
            TcpCodec.write(bytes, 0, 0, remote, local, sequence, 100u, flags, 4096u)
            val intfc = checkNotNull(network.interfaceByName("lo"))
            val packet = IpPacketContext(
                intfc, bytes, remote.address, local.address, IpProtocol.TCP.number, 0, bytes.size, 0,
            )
            protocol.receive(packet)
        }

        fun assertReserved() {
            val result = protocol.bind(replacement, local, false)
            assertEquals(VfsError.ADDRESS_IN_USE, assertIs<VfsResult.Err>(result).error)
        }

        fun assertExpired() {
            assertIs<VfsResult.Ok<Ipv4SocketAddress>>(protocol.bind(replacement, local, false))
            assertIs<VfsResult.Ok<Unit>>(protocol.registerConnection(replacement, local, remote))
        }

        override fun close() {
            protocol.unregister(owner, local, remote)
            protocol.unregister(replacement, local, remote)
            protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
            owner.release()
            replacement.release()
        }
    }

    @Test
    fun retainedBindingSurvivesOldSocketRemovalAndExpires() {
        Retained().use { retained ->
            retained.protocol.unregister(retained.owner, retained.local, retained.remote)
            retained.assertReserved()
            retained.protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
            retained.assertExpired()
        }
    }

    @Test
    fun duplicateFinRestartsTimeWait() {
        Retained().use { retained ->
            val originalExpiry = TscClock.nanoTime() + 60_000_000_000uL
            retained.send(199u, TcpFlags.FIN or TcpFlags.ACK)
            retained.protocol.tick(originalExpiry)
            retained.assertReserved()
            retained.protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
            retained.assertExpired()
        }
    }

    @Test
    fun resetsAndUnrelatedFinDoNotReleaseOrExtendTimeWait() {
        Retained().use { retained ->
            val originalExpiry = TscClock.nanoTime() + 60_000_000_000uL
            retained.send(200u, TcpFlags.RST)
            retained.send(198u, TcpFlags.FIN or TcpFlags.ACK)
            retained.assertReserved()
            retained.protocol.tick(originalExpiry)
            retained.assertExpired()
        }
    }

    @Test
    fun onlyNewSynReplacesRetainedConnectionAcrossSequenceWrap() {
        val process = checkNotNull(ProcessManager.currentProcess())
        val network = NetworkStack()
        val protocol = network.tcp
        val listener = protocol.createSocket()
        val owner = protocol.createSocket()
        val probe = protocol.createSocket()
        val local = Ipv4SocketAddress(Ipv4Address.ANY, 8080u)
        val remote = Ipv4SocketAddress(Ipv4Address.fromBits(0xc0000202u), 40000u)
        assertIs<VfsResult.Ok<Unit>>(listener.bindSocket(process, local))
        assertIs<VfsResult.Ok<Unit>>(listener.listenSocket(process, 4))
        assertIs<VfsResult.Ok<Unit>>(protocol.registerConnection(owner, local, remote))
        val acknowledgment = TcpTransmission(local, remote, 100u, UInt.MAX_VALUE, TcpFlags.ACK, 0u)
        protocol.timeWait(owner, acknowledgment)
        val intfc = checkNotNull(network.interfaceByName("lo"))
        try {
            val invalidFlags = listOf(TcpFlags.SYN or TcpFlags.RST, TcpFlags.SYN or TcpFlags.ACK)
            val attempts = listOf(UInt.MAX_VALUE - 1u to TcpFlags.SYN) +
                invalidFlags.map { 1u to it }
            for ((sequence, flags) in attempts) {
                val bytes = ByteArray(TcpCodec.MIN_HEADER_SIZE)
                TcpCodec.write(bytes, 0, 0, remote, local, sequence, 0u, flags, 4096u)
                val packet = IpPacketContext(
                    intfc, bytes, remote.address, local.address, IpProtocol.TCP.number, 0, bytes.size, 0,
                )
                protocol.receive(packet)
                protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
                assertIs<VfsResult.Ok<Unit>>(protocol.registerConnection(owner, local, remote))
                protocol.timeWait(owner, acknowledgment)
            }
            val bytes = ByteArray(TcpCodec.MIN_HEADER_SIZE)
            TcpCodec.write(bytes, 0, 0, remote, local, 1u, 0u, TcpFlags.SYN, 4096u)
            val packet = IpPacketContext(
                intfc, bytes, remote.address, local.address, IpProtocol.TCP.number, 0, bytes.size, 0,
            )
            protocol.receive(packet)
            protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
            val result = protocol.registerConnection(probe, local, remote)
            assertEquals(VfsError.ADDRESS_IN_USE, assertIs<VfsResult.Err>(result).error)
        } finally {
            listener.release()
            owner.release()
            probe.release()
            protocol.tick(TscClock.nanoTime() + 61_000_000_000uL)
        }
    }

    @Test
    fun reconnectsSameTupleAndPreservesReplacementDuringOldCleanup() {
        val process = checkNotNull(ProcessManager.currentProcess())
        val network = NetworkStack()
        val intfc = checkNotNull(network.interfaceByName("lo"))
        assertIs<VfsResult.Ok<Unit>>(network.setLink(intfc.index, true))
        val listener = network.tcp.createSocket()
        val address = Ipv4SocketAddress(Ipv4Address.fromBits(0x7f000001u), 8080u)
        assertIs<VfsResult.Ok<Unit>>(listener.bindSocket(process, address))
        assertIs<VfsResult.Ok<Unit>>(listener.listenSocket(process, 4))
        var previous: TcpSocket? = null
        var local = Ipv4SocketAddress(address.address, 40000u)
        try {
            repeat(32) { iteration ->
                val deadline = TscClock.nanoTime() + 1_000_000uL
                while (true) {
                    val now = TscClock.nanoTime()
                    if (now >= deadline) break
                }
                val client = network.tcp.createSocket()
                val bound = client.bindSocket(process, local)
                assertEquals(VfsResult.Ok(Unit), bound, "bind $iteration")
                val connected = client.connectSocket(process, address, false)
                assertEquals(VfsResult.Ok(Unit), connected, "connect $iteration")
                local = client.localAddress()
                val accepted = listener.acceptSocket(process, true)
                val peer = assertIs<VfsResult.Ok<AcceptedSocket>>(accepted).value
                val server = assertIs<TcpSocket>(peer.socket)
                previous?.let { old -> network.tcp.unregister(old, address, local) }
                network.tcp.tick(TscClock.nanoTime() + 61_000_000_000uL)
                val bytes = byteArrayOf(iteration.toByte())
                val buffer = ByteArrayBuffer(bytes)
                val source = checkNotNull(buffer.prepareRead(0, 1))
                val send = SocketSendRequest(process, source, 0, 1, nonBlocking = true)
                assertEquals(1, client.sendSocket(send).bytesTransferred)
                val destination = checkNotNull(buffer.prepareWrite(0, 1))
                val receive = SocketReceiveRequest(destination, 0, 1, nonBlocking = true)
                val received = server.receiveSocket(receive)
                assertEquals(1, assertIs<VfsResult.Ok<SocketReceiveResult>>(received).value.bytes)
                assertEquals(iteration.toByte(), bytes[0])
                val shutdown = server.shutdownSocket(SocketShutdownMode.WRITE)
                assertEquals(VfsResult.Ok(Unit), shutdown, "shutdown $iteration")
                client.release()
                server.release()
                previous = server
                assertTrue(listener.isListening())
            }
        } finally {
            listener.release()
            network.tcp.tick(TscClock.nanoTime() + 61_000_000_000uL)
        }
    }
}
