package org.plos_clan.cpos.network

import org.plos_clan.cpos.utils.BigEndianBuffer
import org.plos_clan.cpos.fs.vfs.VfsError

internal class UdpProtocol(val network: NetworkStack) : IpProtocolHandler {
    override val protocol = IpProtocol.UDP
    val ports = PortTable<Ipv4DatagramSocket>()

    init {
        network.registerHandler(this)
    }

    fun createSocket(owner: Int) = UdpSocket(this, owner)

    override fun receive(packet: IpPacketContext) {
        val segment = UdpCodec.decode(
            packet.bytes,
            packet.payloadOffset,
            packet.payloadLength,
            packet.source,
            packet.destination,
            packet.checksumVerified,
        ) ?: return
        val source = Ipv4SocketAddress(packet.source, segment.sourcePort)
        val destination = Ipv4SocketAddress(packet.destination, segment.destinationPort)
        if (destination.address.isMulticast || network.isBroadcast(destination.address)) {
            ports.multicast(destination, source, packet.intfc.index) {
                it.enqueue(packet, source, segment.payloadOffset, segment.payloadLength)
            }
            return
        }
        val socket = ports.select(destination, source, packet.intfc.index)
        if (socket == null) network.sendPortUnreachable(packet)
        else socket.enqueue(packet, source, segment.payloadOffset, segment.payloadLength)
    }

    override fun receiveError(packet: IpPacketContext, error: IpTransportError) {
        if (packet.payloadLength < UdpCodec.HEADER_SIZE) return
        val input = BigEndianBuffer(packet.bytes)
        val sourcePort = input.readU16(packet.payloadOffset)
        val destinationPort = input.readU16(packet.payloadOffset + 2)
        val remote = Ipv4SocketAddress(packet.destination, destinationPort)
        val local = Ipv4SocketAddress(packet.source, sourcePort)
        val recipient = ports.select(local, remote, packet.intfc.index) ?: return
        recipient.reportError(error.datagramError)
    }
}

internal class UdpSocket internal constructor(
    subsystem: UdpProtocol,
    owner: Int,
) : Ipv4DatagramSocket(
    subsystem.network, IpProtocol.UDP, subsystem.ports, owner, UdpCodec.HEADER_SIZE,
) {
    override val supportsInterfaceBinding = true
    override val supportsSocketFilter = true

    override fun normalizePeer(address: Ipv4SocketAddress) =
        address.takeUnless { it.address.isAny || it.port == 0.toUShort() }

    override fun writeHeader(packet: Ipv4OutputPacket, tx: Transmission): VfsError? {
        val length = packet.payloadLength - UdpCodec.HEADER_SIZE
        UdpCodec.write(
            packet.bytes, Ipv4OutputPacket.PAYLOAD_OFFSET, length, tx.source, tx.destination,
            checksumRequired = !tx.path.local,
        )
        return null
    }
}
