package org.plos_clan.cpos.network

import org.plos_clan.cpos.utils.BigEndianBuffer
import kotlin.concurrent.Volatile
import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IndexedHeap
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal data class TcpTransmission(
    val source: Ipv4SocketAddress,
    val destination: Ipv4SocketAddress,
    val sequenceNumber: UInt,
    val acknowledgmentNumber: UInt,
    val flags: Int,
    val window: UShort,
    val options: ByteArray = TcpCodec.EMPTY,
    val payload: PacketSlice = PacketSlice.EMPTY,
    val ttl: UByte = 64u,
    val frame: Ipv4OutputPacket? = null,
)

internal class TcpProtocol(val network: NetworkStack) : IpProtocolHandler {
    interface Connection {
        fun receiveSegment(packet: IpPacketContext, segment: TcpSegment)
        fun reopenSequence(segment: TcpSegment): UInt? = null
    }

    private data class ConnectionKey(
        val local: Ipv4SocketAddress,
        val remote: Ipv4SocketAddress,
    )

    private inner class TimeWait(
        val key: ConnectionKey,
        private val acknowledgment: TcpTransmission,
    ) : Connection {
        var expires = TscClock.nanoTime() + TIME_WAIT_NANOS
            private set
        var timer = timeWaits.add(this)
            private set

        override fun reopenSequence(segment: TcpSegment): UInt? {
            val controls = TcpFlags.SYN or TcpFlags.ACK or TcpFlags.RST or TcpFlags.FIN
            val control = segment.flags and controls
            val newer = TcpSequence.after(segment.sequenceNumber, acknowledgment.acknowledgmentNumber)
            return if (control == TcpFlags.SYN && newer) acknowledgment.sequenceNumber + 1u else null
        }

        override fun receiveSegment(packet: IpPacketContext, segment: TcpSegment) {
            if (segment.flags and TcpFlags.RST != 0) return
            val fin = segment.flags and TcpFlags.FIN != 0
            val end = segment.sequenceNumber + segment.payloadLength.toUInt() + 1u
            val retained = lock.withLock {
                if (connections[key] !== this) return@withLock false
                if (fin && end == acknowledgment.acknowledgmentNumber) {
                    timeWaits.remove(timer)
                    expires = TscClock.nanoTime() + TIME_WAIT_NANOS
                    timer = timeWaits.add(this)
                }
                true
            }
            if (!retained) return
            val control = segment.flags and (TcpFlags.SYN or TcpFlags.FIN or TcpFlags.ACK)
            val confirmed = control == TcpFlags.ACK && segment.payloadLength == 0 &&
                segment.sequenceNumber == acknowledgment.acknowledgmentNumber
            if (!confirmed) transmit(acknowledgment)
        }
    }

    override val protocol = IpProtocol.TCP
    private val lock = IrqSpinLock()
    val ports = PortTable<Connection>()
    @Volatile private var connections = persistentHashMapOf<ConnectionKey, Connection>()
    private val active = mutableSetOf<TcpSocket>()
    private val timeWaits = IndexedHeap<TimeWait> { first, second ->
        first.expires.compareTo(second.expires)
    }
    private val sequences by lazy {
        val bytes = KernelRandom.bytes(16)
        val secret = LittleEndianBuffer(bytes)
        TcpSequence.Generator(secret.readU64(0), secret.readU64(8))
    }

    init {
        network.registerHandler(this)
    }

    fun tick(now: ULong) {
        val sockets = lock.withLock {
            while (true) {
                val expired = timeWaits.peek() ?: break
                if (expired.expires > now) break
                unregisterLocked(expired, expired.key.local, expired.key.remote)
            }
            active.toList()
        }
        sockets.forEach { it.tick(now) }
    }

    fun createSocket(owner: Int) = TcpSocket(this, owner)

    fun initialSequence(local: Ipv4SocketAddress, remote: Ipv4SocketAddress): UInt =
        sequences.initial(local, remote, TscClock.nanoTime())

    fun registerConnection(
        socket: TcpSocket,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
        previous: Connection? = null,
    ): VfsResult<Unit> = lock.withLock {
        val key = ConnectionKey(local, remote)
        val current = connections[key]
        val conflict = current != null && current !== socket && current !== previous
        if (conflict) return@withLock VfsResult.Err(
            VfsError.ADDRESS_IN_USE,
        )
        if (current is TimeWait) unregisterLocked(current, local, remote)
        connections = connections.putting(key, socket)
        active.add(socket)
        VfsResult.Ok(Unit)
    }

    fun timeWait(socket: TcpSocket, acknowledgment: TcpTransmission) = lock.withLock {
        val key = ConnectionKey(acknowledgment.source, acknowledgment.destination)
        if (connections[key] !== socket) return@withLock
        val retained = TimeWait(key, acknowledgment)
        connections = connections.putting(key, retained)
        active.remove(socket)
        ports.remove(key.local.port, socket, retained)
    }

    fun unregister(
        socket: TcpSocket,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress?,
    ) = lock.withLock { unregisterLocked(socket, local, remote) }

    private fun unregisterLocked(
        socket: Connection,
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress?,
    ) {
        if (socket is TcpSocket) active.remove(socket)
        if (socket is TimeWait) timeWaits.remove(socket.timer)
        if (remote != null) {
            val key = ConnectionKey(local, remote)
            if (connections[key] === socket) connections = connections.removing(key)
        }
        ports.remove(local.port, socket)
    }

    override fun receive(packet: IpPacketContext) {
        val segment = TcpCodec.decode(
            packet.bytes,
            packet.payloadOffset,
            packet.payloadLength,
            packet.source,
            packet.destination,
            packet.checksumVerified,
        ) ?: return
        val local = Ipv4SocketAddress(packet.destination, segment.destinationPort)
        val remote = Ipv4SocketAddress(packet.source, segment.sourcePort)
        val connection = connections[ConnectionKey(local, remote)]
        val initialSequence = connection?.reopenSequence(segment)
        if (connection != null && initialSequence == null) {
            connection.receiveSegment(packet, segment)
            return
        }
        val index = packet.intfc.index
        val listener = ports.select(local, remote, index, listening = true) as? TcpSocket
        if (listener != null && segment.flags and TcpFlags.SYN != 0 &&
            segment.flags and TcpFlags.ACK == 0
        ) {
            listener.receiveSyn(packet, segment, local, remote, connection, initialSequence)
            return
        }
        if (connection != null) connection.receiveSegment(packet, segment)
        else sendReset(local, remote, segment)
    }

    override fun receiveError(packet: IpPacketContext, error: IpTransportError) {
        if (packet.payloadLength < TcpCodec.MIN_HEADER_SIZE) return
        val input = BigEndianBuffer(packet.bytes)
        val local = Ipv4SocketAddress(packet.source, input.readU16(packet.payloadOffset))
        val remote = Ipv4SocketAddress(
            packet.destination,
            input.readU16(packet.payloadOffset + 2),
        )
        val socket = connections[ConnectionKey(local, remote)] as? TcpSocket ?: return
        socket.reportError(error.error)
    }

    fun transmit(transmission: TcpTransmission): VfsResult<Unit> {
        val path = network.path(transmission.source.address, transmission.destination.address)
        if (path is VfsResult.Err) return path
        val route = (path as VfsResult.Ok).value
        val headerLength = TcpCodec.MIN_HEADER_SIZE + transmission.options.size
        val length = headerLength + transmission.payload.size
        val packet = transmission.frame ?: Ipv4OutputPacket(length)
        val segment = packet.bytes
        val offset = Ipv4OutputPacket.PAYLOAD_OFFSET
        val payload = transmission.payload
        if (transmission.frame == null && payload.size != 0) {
            val end = payload.offset + payload.size
            payload.bytes.copyInto(segment, offset + headerLength, payload.offset, end)
        }
        TcpCodec.write(
            segment,
            offset,
            transmission.payload.size,
            transmission.source,
            transmission.destination,
            transmission.sequenceNumber,
            transmission.acknowledgmentNumber,
            transmission.flags,
            transmission.window,
            transmission.options,
            checksumRequired = !route.local,
        )
        return network.sendIpv4(
            route, IpProtocol.TCP, packet, dontFragment = true, ttl = transmission.ttl,
        )
    }

    private fun sendReset(
        local: Ipv4SocketAddress,
        remote: Ipv4SocketAddress,
        segment: TcpSegment,
    ) {
        if (segment.flags and TcpFlags.RST != 0) return
        val acknowledges = segment.flags and TcpFlags.ACK == 0
        val consumed = segment.payloadLength +
            (if (segment.flags and TcpFlags.SYN != 0) 1 else 0) +
            (if (segment.flags and TcpFlags.FIN != 0) 1 else 0)
        transmit(
            TcpTransmission(
                local,
                remote,
                if (acknowledges) 0u else segment.acknowledgmentNumber,
                if (acknowledges) segment.sequenceNumber + consumed.toUInt() else 0u,
                TcpFlags.RST or if (acknowledges) TcpFlags.ACK else 0,
                0u,
            ),
        )
    }

    companion object {
        private const val TIME_WAIT_NANOS = 60_000_000_000uL
    }
}
