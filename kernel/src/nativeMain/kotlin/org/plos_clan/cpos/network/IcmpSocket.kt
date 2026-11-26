package org.plos_clan.cpos.network

import org.plos_clan.cpos.utils.BigEndianBuffer
import org.plos_clan.cpos.fs.vfs.VfsError

internal class IcmpProtocol(val network: NetworkStack) {
    val ports = PortTable<Ipv4DatagramSocket>(1..UShort.MAX_VALUE.toInt())

    fun createSocket() = IcmpSocket(this)

    fun receive(packet: IpPacketContext, type: Int, code: Int) {
        if (type != ICMP_ECHO_REPLY || code != 0) return
        val identifier = BigEndianBuffer(packet.bytes).readU16(packet.payloadOffset + 4)
        val source = Ipv4SocketAddress(packet.source, 0u)
        val local = Ipv4SocketAddress(packet.destination, identifier)
        val socket = ports.select(local, source, packet.intfc.index) ?: return
        socket.enqueue(packet, source, packet.payloadOffset, packet.payloadLength)
    }

    private companion object {
        const val ICMP_ECHO_REPLY = 0
    }
}

internal class IcmpSocket(subsystem: IcmpProtocol) : Ipv4DatagramSocket(
    subsystem.network, IpProtocol.ICMP, subsystem.ports,
    owner = null, headerSize = 0, minimumSize = 8,
) {
    override fun normalizePeer(address: Ipv4SocketAddress): Ipv4SocketAddress? {
        if (!address.address.isUnicast) return null
        return if (address.port == 0.toUShort()) address else address.copy(port = 0u)
    }

    override fun writeHeader(packet: Ipv4OutputPacket, tx: Transmission): VfsError? {
        val offset = Ipv4OutputPacket.PAYLOAD_OFFSET
        val output = BigEndianBuffer(packet.bytes)
        val type = output.readU8(offset).toInt()
        if (type != ICMP_ECHO_REQUEST || output.readU8(offset + 1).toInt() != 0) {
            return VfsError.NOT_PERMITTED
        }
        output.writeU16(offset + 2, 0u)
        output.writeU16(offset + 4, tx.source.port)
        if (!tx.path.local) {
            val checksum = InternetChecksum.compute(packet.bytes, offset)
            output.writeU16(offset + 2, checksum)
        }
        return null
    }

    private companion object {
        const val ICMP_ECHO_REQUEST = 8
    }
}
