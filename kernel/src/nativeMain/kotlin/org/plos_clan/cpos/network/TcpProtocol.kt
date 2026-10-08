package org.plos_clan.cpos.network

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
    val payload: ByteArray = TcpCodec.EMPTY,
    val ttl: UByte = 64u,
)

internal class TcpProtocol(val network: NetworkStack) : IpProtocolHandler {
    interface Connection {
        fun receiveSegment(packet: IpPacketContext, segment: TcpSegment)
        fun reopenSequence(segment: TcpSegment): UInt? = null
    }

    private data class Binding(
        val socket: Connection,
        val address: Ipv4SocketAddress,
        val reuseAddress: Boolean,
    )

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
    private val bindings = mutableMapOf<UShort, MutableList<Binding>>()
    private val listeners = mutableMapOf<UShort, MutableList<TcpSocket>>()
    private val connections = mutableMapOf<ConnectionKey, Connection>()
    private val active = mutableSetOf<TcpSocket>()
    private val timeWaits = IndexedHeap<TimeWait> { first, second ->
        first.expires.compareTo(second.expires)
    }
    private var nextEphemeralPort = EPHEMERAL_PORT_FIRST
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

    fun createSocket(): TcpSocket = TcpSocket(this)

    fun initialSequence(local: Ipv4SocketAddress, remote: Ipv4SocketAddress): UInt =
        sequences.initial(local, remote, TscClock.nanoTime())

    fun bind(
        socket: TcpSocket,
        requested: Ipv4SocketAddress,
        reuseAddress: Boolean,
    ): VfsResult<Ipv4SocketAddress> = lock.withLock {
        if (requested.port != 0.toUShort()) return@withLock bindPort(
            socket,
            requested,
            reuseAddress,
        )
        repeat(EPHEMERAL_PORT_COUNT) {
            val address = requested.copy(port = nextEphemeralPort.toUShort())
            nextEphemeralPort = if (nextEphemeralPort == EPHEMERAL_PORT_LAST) {
                EPHEMERAL_PORT_FIRST
            } else nextEphemeralPort + 1
            val result = bindPort(socket, address, reuseAddress)
            if (result is VfsResult.Ok) return@withLock result
        }
        VfsResult.Err(VfsError.ADDRESS_IN_USE)
    }

    fun listen(socket: TcpSocket, address: Ipv4SocketAddress): VfsResult<Unit> = lock.withLock {
        val entries = listeners.getOrPut(address.port) { mutableListOf() }
        if (socket !in entries) entries += socket
        VfsResult.Ok(Unit)
    }

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
        connections[key] = socket
        active.add(socket)
        VfsResult.Ok(Unit)
    }

    fun timeWait(socket: TcpSocket, acknowledgment: TcpTransmission) = lock.withLock {
        val key = ConnectionKey(acknowledgment.source, acknowledgment.destination)
        if (connections[key] !== socket) return@withLock
        val retained = TimeWait(key, acknowledgment)
        connections[key] = retained
        active.remove(socket)
        val bound = bindings[key.local.port] ?: return@withLock
        val index = bound.indexOfFirst { it.socket === socket }
        if (index >= 0) bound[index] = bound[index].copy(socket = retained)
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
            if (connections[key] === socket) connections.remove(key)
        }
        val listening = listeners[local.port]
        listening?.removeAll { it === socket }
        if (listening?.isEmpty() == true) listeners.remove(local.port)
        val bound = bindings[local.port]
        bound?.removeAll { it.socket === socket }
        if (bound?.isEmpty() == true) bindings.remove(local.port)
    }

    override fun receive(packet: IpPacketContext) {
        val segment = TcpCodec.decode(
            packet.bytes,
            packet.payloadOffset,
            packet.payloadLength,
            packet.source,
            packet.destination,
        ) ?: return
        val local = Ipv4SocketAddress(packet.destination, segment.destinationPort)
        val remote = Ipv4SocketAddress(packet.source, segment.sourcePort)
        val connection = lock.withLock { connections[ConnectionKey(local, remote)] }
        val initialSequence = connection?.reopenSequence(segment)
        if (connection != null && initialSequence == null) {
            connection.receiveSegment(packet, segment)
            return
        }
        val listenerCandidates = lock.withLock { listeners[local.port]?.toList().orEmpty() }
        val listener = listenerCandidates.firstOrNull {
            val bound = it.boundAddress()
            bound.address.isAny || bound.address == local.address
        }
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
        val input = NetworkOrderBuffer(packet.bytes)
        val local = Ipv4SocketAddress(packet.source, input.readU16(packet.payloadOffset))
        val remote = Ipv4SocketAddress(
            packet.destination,
            input.readU16(packet.payloadOffset + 2),
        )
        val socket = lock.withLock { connections[ConnectionKey(local, remote)] as? TcpSocket } ?: return
        socket.reportError(
            when (error) {
                IpTransportError.NETWORK_UNREACHABLE -> VfsError.NETWORK_UNREACHABLE
                IpTransportError.HOST_UNREACHABLE -> VfsError.HOST_UNREACHABLE
                IpTransportError.PORT_UNREACHABLE -> VfsError.CONNECTION_REFUSED
                IpTransportError.FRAGMENTATION_NEEDED -> VfsError.MESSAGE_TOO_LONG
                IpTransportError.PROTOCOL_UNREACHABLE -> VfsError.PROTOCOL_NOT_SUPPORTED
                IpTransportError.TIME_EXCEEDED -> VfsError.TIMED_OUT
            },
        )
    }

    fun transmit(transmission: TcpTransmission): VfsResult<Unit> {
        val headerLength = TcpCodec.MIN_HEADER_SIZE + transmission.options.size
        val packet = Ipv4OutputPacket(headerLength + transmission.payload.size)
        val segment = packet.bytes
        val offset = Ipv4OutputPacket.PAYLOAD_OFFSET
        transmission.payload.copyInto(
            segment,
            offset + headerLength,
        )
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
        )
        return when (val result = network.sendIpv4(
            transmission.source.address,
            transmission.destination.address,
            IpProtocol.TCP,
            packet,
            dontFragment = true,
            ttl = transmission.ttl,
        )) {
            is VfsResult.Ok -> VfsResult.Ok(Unit)
            is VfsResult.Err -> result
        }
    }

    private fun bindPort(
        socket: TcpSocket,
        address: Ipv4SocketAddress,
        reuseAddress: Boolean,
    ): VfsResult<Ipv4SocketAddress> {
        val entries = bindings.getOrPut(address.port) { mutableListOf() }
        val conflict = entries.any { existing ->
            val overlaps = existing.address.address.isAny || address.address.isAny ||
                existing.address.address == address.address
            overlaps && (!existing.reuseAddress || !reuseAddress)
        }
        if (conflict) return VfsResult.Err(VfsError.ADDRESS_IN_USE)
        entries += Binding(socket, address, reuseAddress)
        return VfsResult.Ok(address)
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
        private const val EPHEMERAL_PORT_FIRST = 32_768
        private const val EPHEMERAL_PORT_LAST = 60_999
        private const val EPHEMERAL_PORT_COUNT = EPHEMERAL_PORT_LAST - EPHEMERAL_PORT_FIRST + 1
    }
}
