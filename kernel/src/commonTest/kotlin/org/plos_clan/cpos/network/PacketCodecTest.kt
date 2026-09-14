package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketCodecTest {
    private val source = requireNotNull(Ipv4Address.parse("192.0.2.1"))
    private val destination = requireNotNull(Ipv4Address.parse("198.51.100.9"))

    @Test
    fun ipv4AddressAndPrefixUseCanonicalNetworkBits() {
        assertEquals("192.0.2.1", source.toString())
        assertNull(Ipv4Address.parse("192.0.2"))
        assertNull(Ipv4Address.parse("256.0.2.1"))

        val prefix = Ipv4Prefix(source, 24)
        assertEquals("192.0.2.0", prefix.network.toString())
        assertEquals("192.0.2.255", prefix.broadcast.toString())
        assertTrue(prefix.contains(requireNotNull(Ipv4Address.parse("192.0.2.254"))))
        assertFalse(prefix.contains(destination))
    }

    @Test
    fun arpRoundTripPreservesProtocolAndHardwareAddresses() {
        val sender = requireNotNull(MacAddress.from(byteArrayOf(2, 0, 0, 0, 0, 1)))
        val packet = ArpPacket(
            ArpOperation.REQUEST,
            sender,
            source,
            MacAddress.ZERO,
            destination,
        )
        val bytes = ByteArray(ArpPacket.SIZE)
        packet.writeTo(bytes)

        assertEquals(packet, ArpPacket.decode(bytes))
        bytes[4] = 5
        assertNull(ArpPacket.decode(bytes))
    }

    @Test
    fun ipv4HeaderRoundTripValidatesChecksumAndFragmentUnits() {
        val bytes = ByteArray(Ipv4Codec.MIN_HEADER_SIZE + 16)
        Ipv4Codec.writeHeader(
            bytes,
            0,
            16,
            source,
            destination,
            IpProtocol.UDP.number,
            0x1234u,
            fragmentOffset = 24,
            moreFragments = true,
        )
        val packet = assertNotNull(Ipv4Codec.decode(bytes))

        assertEquals(source, packet.source)
        assertEquals(destination, packet.destination)
        assertEquals(24, packet.fragmentOffset)
        assertTrue(packet.moreFragments)
        assertEquals(16, packet.payloadLength)

        bytes[8] = 63
        assertNull(Ipv4Codec.decode(bytes))
    }

    @Test
    fun udpChecksumCoversPseudoHeaderAndOddPayload() {
        val payload = "hello".encodeToByteArray()
        val bytes = ByteArray(UdpCodec.HEADER_SIZE + payload.size)
        payload.copyInto(bytes, UdpCodec.HEADER_SIZE)
        val local = Ipv4SocketAddress(source, 12345u)
        val remote = Ipv4SocketAddress(destination, 53u)
        UdpCodec.write(bytes, 0, payload.size, local, remote)

        val segment = assertNotNull(UdpCodec.decode(bytes, 0, bytes.size, source, destination))
        assertEquals(local.port, segment.sourcePort)
        assertEquals(remote.port, segment.destinationPort)
        assertContentEquals(payload, bytes.copyOfRange(segment.payloadOffset, bytes.size))

        bytes.lastIndex.also { bytes[it] = (bytes[it].toInt() xor 1).toByte() }
        assertNull(UdpCodec.decode(bytes, 0, bytes.size, source, destination))
    }

    @Test
    fun tcpRoundTripParsesStandardSynOptionsAndPayload() {
        val options = TcpCodec.synOptions(1460u, 7u)
        val payload = byteArrayOf(1, 2, 3, 4)
        val bytes = ByteArray(TcpCodec.MIN_HEADER_SIZE + options.size + payload.size)
        payload.copyInto(bytes, TcpCodec.MIN_HEADER_SIZE + options.size)
        val local = Ipv4SocketAddress(source, 40000u)
        val remote = Ipv4SocketAddress(destination, 443u)
        TcpCodec.write(
            bytes,
            0,
            payload.size,
            local,
            remote,
            0x1020_3040u,
            0x5060_7080u,
            TcpFlags.SYN or TcpFlags.ACK,
            32768u,
            options,
        )
        val segment = assertNotNull(TcpCodec.decode(bytes, 0, bytes.size, source, destination))

        assertEquals(1460u.toUShort(), segment.options.maximumSegmentSize)
        assertEquals(7u.toUByte(), segment.options.windowScale)
        assertTrue(segment.options.sackPermitted)
        assertEquals(TcpFlags.SYN or TcpFlags.ACK, segment.flags)
        assertContentEquals(payload, bytes.copyOfRange(segment.payloadOffset, bytes.size))
    }

    @Test
    fun netlinkMessagesAndAttributesHonorFourByteAlignment() {
        val attributes = listOf(
            NetlinkAttribute.binary(1, byteArrayOf(7)),
            NetlinkAttribute.u32(2, 0x1234_5678u),
        )
        val payload = NetlinkCodec.payload(ByteArray(4), attributes)
        val message = NetlinkCodec.encode(24, 0x301, 99u, payload = payload)
        val decoded = NetlinkCodec.decode(message).single()
        val reply = NetlinkCodec.encode(24, 0x301, 99u, 4242u, payload)
        val decodedReply = NetlinkCodec.decode(reply).single()
        val decodedAttributes = assertNotNull(decoded.attributes(4))

        assertContentEquals(byteArrayOf(7), requireNotNull(decodedAttributes[1]).payload.copy())
        assertEquals(0x1234_5678u, requireNotNull(decodedAttributes[2]).u32())
        assertEquals(0u, decoded.portId)
        assertEquals(4242u, decodedReply.portId)
        assertEquals(0, message.size and 3)
    }

    @Test
    fun netlinkAttributesPreserveDuplicatesAndNestedTypeFlags() {
        val payload = NetlinkCodec.payload(
            attributes = listOf(
                NetlinkAttribute.binary(1, byteArrayOf(1)),
                NetlinkAttribute.binary(1, byteArrayOf(2)),
                NetlinkAttribute.nested(
                    2,
                    listOf(NetlinkAttribute.u16(3, 7u)),
                ),
            ),
        )
        val message = NetlinkCodec.encode(16, 0, 1u, payload = payload)
        val attributes = assertNotNull(NetlinkCodec.decode(message).single().attributes())

        assertEquals(2, attributes.all(1).size)
        assertContentEquals(byteArrayOf(2), requireNotNull(attributes[1]).payload.copy())
        val nested = requireNotNull(attributes[2])
        assertTrue(nested.nested)
        assertEquals(7u.toUShort(), requireNotNull(nested.attributes()?.get(3)).u16())
    }

    @Test
    fun netlinkDecoderAcceptsAlignedDatagramPaddingOutsideMessageLength() {
        val encoded = NetlinkCodec.encode(18, 0x301, 3u, payload = byteArrayOf(0))
        val second = NetlinkCodec.encode(3, 2, 3u, payload = ByteArray(Int.SIZE_BYTES))
        val batch = encoded.copyOf(20) + second
        val decoded = NetlinkCodec.decode(batch)

        assertEquals(2, decoded.size)
        assertEquals(17, decoded.first().raw.size)
        assertContentEquals(byteArrayOf(0), decoded.first().payload.copy())
    }

    @Test
    fun netlinkDecoderProcessesValidMessageBeforeOversizedZeroTail() {
        val request = NetlinkCodec.encode(22, 0x301, 4u, payload = ByteArray(8))
        val decoded = NetlinkCodec.decode(request.copyOf(152))

        assertEquals(1, decoded.size)
        assertEquals(22u.toUShort(), decoded.single().type)
        assertEquals(24, decoded.single().raw.size)
    }

    @Test
    fun netlinkDoneCarriesZeroStatusWord() {
        val done = NetlinkCodec.encode(3, 2, 99u, payload = ByteArray(Int.SIZE_BYTES))
        val decoded = NetlinkCodec.decode(done).single()

        assertEquals(Int.SIZE_BYTES, decoded.payload.size)
        assertEquals(0u, decoded.payload.readU32(0))
    }

    @Test
    fun packetSocketAddressPreservesNetworkByteOrderAndLinkMetadata() {
        val address = PacketSocketAddress(
            interfaceIndex = 2,
            protocol = EthernetType.IPV4.value,
            hardwareType = NetworkInterfaceKind.ETHERNET.hardwareType,
            packetType = 1u,
            hardwareAddress = MacAddress.BROADCAST,
        )

        assertEquals(VfsResult.Ok(address), SocketAddressAbi.decode(SocketAddressAbi.encode(address)))
    }
}
