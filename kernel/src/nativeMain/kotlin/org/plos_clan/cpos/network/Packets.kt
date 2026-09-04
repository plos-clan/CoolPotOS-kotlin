package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDomain

internal value class NetworkOrderBuffer(private val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    fun readU8(offset: Int): UByte {
        requireRange(offset, UByte.SIZE_BYTES)
        return bytes[offset].toUByte()
    }

    fun readU16(offset: Int): UShort {
        requireRange(offset, UShort.SIZE_BYTES)
        return (
            (bytes[offset].toUByte().toUInt() shl 8) or
                bytes[offset + 1].toUByte().toUInt()
            ).toUShort()
    }

    fun readU32(offset: Int): UInt {
        requireRange(offset, UInt.SIZE_BYTES)
        return (bytes[offset].toUByte().toUInt() shl 24) or
            (bytes[offset + 1].toUByte().toUInt() shl 16) or
            (bytes[offset + 2].toUByte().toUInt() shl 8) or
            bytes[offset + 3].toUByte().toUInt()
    }

    fun writeU8(offset: Int, value: UByte) {
        requireRange(offset, UByte.SIZE_BYTES)
        bytes[offset] = value.toByte()
    }

    fun writeU16(offset: Int, value: UShort) {
        requireRange(offset, UShort.SIZE_BYTES)
        val bits = value.toUInt()
        bytes[offset] = (bits shr 8).toByte()
        bytes[offset + 1] = bits.toByte()
    }

    fun writeU32(offset: Int, value: UInt) {
        requireRange(offset, UInt.SIZE_BYTES)
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun requireRange(offset: Int, length: Int) {
        require(offset >= 0 && offset <= bytes.size - length)
    }
}

internal value class Ipv4Address private constructor(val value: UInt) {
    val isAny: Boolean
        get() = value == 0u
    val isLimitedBroadcast: Boolean
        get() = value == UInt.MAX_VALUE
    val isMulticast: Boolean
        get() = value and 0xF000_0000u == 0xE000_0000u
    val isUnicast: Boolean
        get() = !isAny && !isLimitedBroadcast && !isMulticast

    operator fun get(index: Int): UByte {
        require(index in 0 until SIZE_BYTES)
        return (value shr ((SIZE_BYTES - index - 1) * Byte.SIZE_BITS)).toUByte()
    }

    fun writeTo(bytes: ByteArray, offset: Int = 0) {
        NetworkOrderBuffer(bytes).writeU32(offset, value)
    }

    override fun toString(): String = (0 until SIZE_BYTES).joinToString(".") { this[it].toString() }

    companion object {
        const val SIZE_BYTES = UInt.SIZE_BYTES
        val ANY = Ipv4Address(0u)
        val LIMITED_BROADCAST = Ipv4Address(UInt.MAX_VALUE)

        fun fromBits(value: UInt): Ipv4Address = Ipv4Address(value)

        fun from(bytes: ByteArray, offset: Int = 0): Ipv4Address? {
            if (offset < 0 || offset > bytes.size - SIZE_BYTES) return null
            return Ipv4Address(NetworkOrderBuffer(bytes).readU32(offset))
        }

        fun parse(text: String): Ipv4Address? {
            var value = 0u
            var octet = 0
            var digits = 0
            var count = 0
            for (index in 0..text.length) {
                val character = text.getOrNull(index)
                if (character == '.') {
                    if (digits == 0 || count >= 3) return null
                    value = (value shl 8) or octet.toUInt()
                    octet = 0
                    digits = 0
                    count++
                } else if (character == null) {
                    if (digits == 0 || count != 3) return null
                    return Ipv4Address((value shl 8) or octet.toUInt())
                } else {
                    val digit = character.code - '0'.code
                    if (digit !in 0..9 || digits == 3 || octet > (255 - digit) / 10) return null
                    octet = octet * 10 + digit
                    digits++
                }
            }
            return null
        }
    }
}

internal data class Ipv4Prefix(
    val address: Ipv4Address,
    val length: Int,
) {
    val mask: UInt = when (length) {
        0 -> 0u
        in 1..32 -> UInt.MAX_VALUE shl (32 - length)
        else -> throw IllegalArgumentException("IPv4 prefix length must be in 0..32")
    }
    val network = Ipv4Address.fromBits(address.value and mask)
    val broadcast = Ipv4Address.fromBits(network.value or mask.inv())

    fun contains(candidate: Ipv4Address): Boolean = candidate.value and mask == network.value
}

internal data class Ipv4SocketAddress(
    val address: Ipv4Address,
    val port: UShort,
) : SocketAddress {
    override val domain = SocketDomain.IPV4
}

internal enum class IpProtocol(val number: UByte) {
    ICMP(1u),
    TCP(6u),
    UDP(17u),
    ;

    companion object {
        fun fromNumber(number: UByte): IpProtocol? = entries.firstOrNull { it.number == number }
    }
}

internal object InternetChecksum {
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): UShort =
        finalize(sum(bytes, offset, length))

    fun transport(
        source: Ipv4Address,
        destination: Ipv4Address,
        protocol: UByte,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): UShort {
        require(length <= UShort.MAX_VALUE.toInt())
        var accumulator = source.value.toULong().let {
            (it shr 16) + (it and 0xFFFFuL)
        }
        accumulator += destination.value.toULong().let {
            (it shr 16) + (it and 0xFFFFuL)
        }
        accumulator += protocol.toULong() + length.toULong()
        return finalize(sum(bytes, offset, length, accumulator))
    }

    fun valid(bytes: ByteArray, offset: Int, length: Int): Boolean =
        compute(bytes, offset, length) == 0.toUShort()

    fun validTransport(
        source: Ipv4Address,
        destination: Ipv4Address,
        protocol: UByte,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean = transport(source, destination, protocol, bytes, offset, length) == 0.toUShort()

    private fun sum(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        initial: ULong = 0uL,
    ): ULong {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var accumulator = initial
        var cursor = offset
        val end = offset + length
        while (cursor + 1 < end) {
            accumulator += (
                (bytes[cursor].toUByte().toUInt() shl 8) or
                    bytes[cursor + 1].toUByte().toUInt()
                ).toULong()
            cursor += 2
        }
        if (cursor < end) accumulator += bytes[cursor].toUByte().toULong() shl 8
        return accumulator
    }

    private fun finalize(sum: ULong): UShort {
        var folded = sum
        while (folded shr 16 != 0uL) folded = (folded and 0xFFFFuL) + (folded shr 16)
        return folded.inv().toUShort()
    }
}

internal enum class EthernetType(val value: UShort) {
    IPV4(0x0800u),
    ARP(0x0806u),
}

internal data class EthernetHeader(
    val destination: MacAddress,
    val source: MacAddress,
    val type: UShort,
) {
    fun writeTo(frame: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset <= frame.size - SIZE)
        destination.copyTo(frame, offset)
        source.copyTo(frame, offset + MacAddress.SIZE_BYTES)
        NetworkOrderBuffer(frame).writeU16(offset + 12, type)
    }

    companion object {
        const val SIZE = 14

        fun decode(frame: ByteArray, offset: Int = 0, length: Int = frame.size - offset): EthernetHeader? {
            if (offset < 0 || length < SIZE || offset > frame.size - length) return null
            return EthernetHeader(
                MacAddress.from(frame, offset) ?: return null,
                MacAddress.from(frame, offset + MacAddress.SIZE_BYTES) ?: return null,
                NetworkOrderBuffer(frame).readU16(offset + 12),
            )
        }
    }
}

internal enum class ArpOperation(val value: UShort) {
    REQUEST(1u),
    REPLY(2u),
    ;

    companion object {
        fun fromValue(value: UShort): ArpOperation? = entries.firstOrNull { it.value == value }
    }
}

internal data class ArpPacket(
    val operation: ArpOperation,
    val senderHardwareAddress: MacAddress,
    val senderProtocolAddress: Ipv4Address,
    val targetHardwareAddress: MacAddress,
    val targetProtocolAddress: Ipv4Address,
) {
    fun writeTo(bytes: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset <= bytes.size - SIZE)
        val output = NetworkOrderBuffer(bytes)
        output.writeU16(offset, ETHERNET_HARDWARE_TYPE)
        output.writeU16(offset + 2, EthernetType.IPV4.value)
        output.writeU8(offset + 4, MacAddress.SIZE_BYTES.toUByte())
        output.writeU8(offset + 5, Ipv4Address.SIZE_BYTES.toUByte())
        output.writeU16(offset + 6, operation.value)
        senderHardwareAddress.copyTo(bytes, offset + 8)
        senderProtocolAddress.writeTo(bytes, offset + 14)
        targetHardwareAddress.copyTo(bytes, offset + 18)
        targetProtocolAddress.writeTo(bytes, offset + 24)
    }

    companion object {
        const val SIZE = 28
        private val ETHERNET_HARDWARE_TYPE = 1u.toUShort()

        fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ArpPacket? {
            if (offset < 0 || length < SIZE || offset > bytes.size - length) return null
            val input = NetworkOrderBuffer(bytes)
            if (input.readU16(offset) != ETHERNET_HARDWARE_TYPE ||
                input.readU16(offset + 2) != EthernetType.IPV4.value ||
                input.readU8(offset + 4).toInt() != MacAddress.SIZE_BYTES ||
                input.readU8(offset + 5).toInt() != Ipv4Address.SIZE_BYTES
            ) return null
            return ArpPacket(
                ArpOperation.fromValue(input.readU16(offset + 6)) ?: return null,
                MacAddress.from(bytes, offset + 8) ?: return null,
                Ipv4Address.from(bytes, offset + 14) ?: return null,
                MacAddress.from(bytes, offset + 18) ?: return null,
                Ipv4Address.from(bytes, offset + 24) ?: return null,
            )
        }
    }
}

internal data class Ipv4Packet(
    val source: Ipv4Address,
    val destination: Ipv4Address,
    val protocol: UByte,
    val identification: UShort,
    val ttl: UByte,
    val payloadOffset: Int,
    val payloadLength: Int,
    val fragmentOffset: Int,
    val moreFragments: Boolean,
    val dontFragment: Boolean,
)

internal object Ipv4Codec {
    const val MIN_HEADER_SIZE = 20
    const val MAX_PACKET_SIZE = 65_535

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Ipv4Packet? {
        if (offset < 0 || length < MIN_HEADER_SIZE || offset > bytes.size - length) return null
        val input = NetworkOrderBuffer(bytes)
        val versionAndLength = input.readU8(offset).toInt()
        if (versionAndLength ushr 4 != 4) return null
        val headerLength = (versionAndLength and 0xF) * UInt.SIZE_BYTES
        if (headerLength < MIN_HEADER_SIZE || headerLength > length) return null
        val totalLength = input.readU16(offset + 2).toInt()
        if (totalLength < headerLength || totalLength > length ||
            !InternetChecksum.valid(bytes, offset, headerLength)
        ) return null
        val fragmentation = input.readU16(offset + 6).toInt()
        if (fragmentation and 0x8000 != 0) return null
        val fragmentOffset = (fragmentation and 0x1FFF) * 8
        val payloadLength = totalLength - headerLength
        if (fragmentOffset > MAX_PACKET_SIZE - headerLength - payloadLength ||
            fragmentation and 0x2000 != 0 && (payloadLength == 0 || payloadLength and 7 != 0)
        ) return null
        return Ipv4Packet(
            Ipv4Address.fromBits(input.readU32(offset + 12)),
            Ipv4Address.fromBits(input.readU32(offset + 16)),
            input.readU8(offset + 9),
            input.readU16(offset + 4),
            input.readU8(offset + 8),
            offset + headerLength,
            payloadLength,
            fragmentOffset,
            fragmentation and 0x2000 != 0,
            fragmentation and 0x4000 != 0,
        )
    }

    fun writeHeader(
        bytes: ByteArray,
        offset: Int,
        payloadLength: Int,
        source: Ipv4Address,
        destination: Ipv4Address,
        protocol: UByte,
        identification: UShort,
        ttl: UByte = 64u,
        fragmentOffset: Int = 0,
        moreFragments: Boolean = false,
        dontFragment: Boolean = false,
        typeOfService: UByte = 0u,
    ) {
        require(payloadLength >= 0 && payloadLength <= MAX_PACKET_SIZE - MIN_HEADER_SIZE)
        require(fragmentOffset >= 0 && fragmentOffset and 7 == 0 && fragmentOffset / 8 <= 0x1FFF)
        require(offset >= 0 && offset <= bytes.size - MIN_HEADER_SIZE - payloadLength)
        val output = NetworkOrderBuffer(bytes)
        output.writeU8(offset, 0x45u)
        output.writeU8(offset + 1, typeOfService)
        output.writeU16(offset + 2, (MIN_HEADER_SIZE + payloadLength).toUShort())
        output.writeU16(offset + 4, identification)
        val fragmentation = (fragmentOffset / 8) or
            (if (moreFragments) 0x2000 else 0) or
            (if (dontFragment) 0x4000 else 0)
        output.writeU16(offset + 6, fragmentation.toUShort())
        output.writeU8(offset + 8, ttl)
        output.writeU8(offset + 9, protocol)
        output.writeU16(offset + 10, 0u)
        output.writeU32(offset + 12, source.value)
        output.writeU32(offset + 16, destination.value)
        output.writeU16(offset + 10, InternetChecksum.compute(bytes, offset, MIN_HEADER_SIZE))
    }
}

internal data class UdpSegment(
    val sourcePort: UShort,
    val destinationPort: UShort,
    val payloadOffset: Int,
    val payloadLength: Int,
)

internal object UdpCodec {
    const val HEADER_SIZE = 8

    fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        source: Ipv4Address,
        destination: Ipv4Address,
    ): UdpSegment? {
        if (offset < 0 || length < HEADER_SIZE || offset > bytes.size - length) return null
        val input = NetworkOrderBuffer(bytes)
        val segmentLength = input.readU16(offset + 4).toInt()
        if (segmentLength < HEADER_SIZE || segmentLength > length) return null
        val checksum = input.readU16(offset + 6)
        if (checksum != 0.toUShort() && !InternetChecksum.validTransport(
                source,
                destination,
                IpProtocol.UDP.number,
                bytes,
                offset,
                segmentLength,
            )
        ) return null
        return UdpSegment(
            input.readU16(offset),
            input.readU16(offset + 2),
            offset + HEADER_SIZE,
            segmentLength - HEADER_SIZE,
        )
    }

    fun write(
        bytes: ByteArray,
        offset: Int,
        payloadLength: Int,
        source: Ipv4SocketAddress,
        destination: Ipv4SocketAddress,
    ) {
        val length = HEADER_SIZE + payloadLength
        require(payloadLength >= 0 && length <= UShort.MAX_VALUE.toInt())
        require(offset >= 0 && offset <= bytes.size - length)
        val output = NetworkOrderBuffer(bytes)
        output.writeU16(offset, source.port)
        output.writeU16(offset + 2, destination.port)
        output.writeU16(offset + 4, length.toUShort())
        output.writeU16(offset + 6, 0u)
        val checksum = InternetChecksum.transport(
            source.address,
            destination.address,
            IpProtocol.UDP.number,
            bytes,
            offset,
            length,
        )
        output.writeU16(offset + 6, if (checksum == 0.toUShort()) UShort.MAX_VALUE else checksum)
    }
}

internal object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
    const val ECE = 0x40
    const val CWR = 0x80
}

internal data class TcpOptions(
    val maximumSegmentSize: UShort? = null,
    val windowScale: UByte? = null,
    val sackPermitted: Boolean = false,
)

internal data class TcpSegment(
    val sourcePort: UShort,
    val destinationPort: UShort,
    val sequenceNumber: UInt,
    val acknowledgmentNumber: UInt,
    val flags: Int,
    val window: UShort,
    val payloadOffset: Int,
    val payloadLength: Int,
    val options: TcpOptions,
)

internal object TcpCodec {
    const val MIN_HEADER_SIZE = 20
    const val MAX_HEADER_SIZE = 60

    fun decode(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        source: Ipv4Address,
        destination: Ipv4Address,
    ): TcpSegment? {
        if (offset < 0 || length < MIN_HEADER_SIZE || offset > bytes.size - length) return null
        val input = NetworkOrderBuffer(bytes)
        val headerLength = (input.readU8(offset + 12).toInt() ushr 4) * UInt.SIZE_BYTES
        if (headerLength !in MIN_HEADER_SIZE..minOf(MAX_HEADER_SIZE, length) ||
            !InternetChecksum.validTransport(
                source,
                destination,
                IpProtocol.TCP.number,
                bytes,
                offset,
                length,
            )
        ) return null
        return TcpSegment(
            input.readU16(offset),
            input.readU16(offset + 2),
            input.readU32(offset + 4),
            input.readU32(offset + 8),
            input.readU8(offset + 13).toInt(),
            input.readU16(offset + 14),
            offset + headerLength,
            length - headerLength,
            parseOptions(bytes, offset + MIN_HEADER_SIZE, headerLength - MIN_HEADER_SIZE)
                ?: return null,
        )
    }

    fun write(
        bytes: ByteArray,
        offset: Int,
        payloadLength: Int,
        source: Ipv4SocketAddress,
        destination: Ipv4SocketAddress,
        sequenceNumber: UInt,
        acknowledgmentNumber: UInt,
        flags: Int,
        window: UShort,
        options: ByteArray = ByteArray(0),
    ) {
        require(options.size and 3 == 0 && options.size <= MAX_HEADER_SIZE - MIN_HEADER_SIZE)
        val headerLength = MIN_HEADER_SIZE + options.size
        val length = headerLength + payloadLength
        require(offset >= 0 && payloadLength >= 0 && offset <= bytes.size - length)
        val output = NetworkOrderBuffer(bytes)
        output.writeU16(offset, source.port)
        output.writeU16(offset + 2, destination.port)
        output.writeU32(offset + 4, sequenceNumber)
        output.writeU32(offset + 8, acknowledgmentNumber)
        output.writeU8(offset + 12, ((headerLength / UInt.SIZE_BYTES) shl 4).toUByte())
        output.writeU8(offset + 13, flags.toUByte())
        output.writeU16(offset + 14, window)
        output.writeU16(offset + 16, 0u)
        output.writeU16(offset + 18, 0u)
        options.copyInto(bytes, offset + MIN_HEADER_SIZE)
        output.writeU16(
            offset + 16,
            InternetChecksum.transport(
                source.address,
                destination.address,
                IpProtocol.TCP.number,
                bytes,
                offset,
                length,
            ),
        )
    }

    fun synOptions(maximumSegmentSize: UShort, windowScale: UByte = 0u): ByteArray = byteArrayOf(
        2, 4, (maximumSegmentSize.toUInt() shr 8).toByte(), maximumSegmentSize.toByte(),
        3, 3, minOf(windowScale.toInt(), 14).toByte(),
        4, 2,
        1, 1, 1,
    )

    private fun parseOptions(bytes: ByteArray, offset: Int, length: Int): TcpOptions? {
        var maximumSegmentSize: UShort? = null
        var windowScale: UByte? = null
        var sackPermitted = false
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            when (val kind = bytes[cursor].toUByte().toInt()) {
                0 -> break
                1 -> cursor++
                else -> {
                    if (cursor + 1 >= end) return null
                    val optionLength = bytes[cursor + 1].toUByte().toInt()
                    if (optionLength < 2 || cursor > end - optionLength) return null
                    when {
                        kind == 2 && optionLength == 4 -> maximumSegmentSize =
                            NetworkOrderBuffer(bytes).readU16(cursor + 2)
                        kind == 3 && optionLength == 3 -> windowScale =
                            minOf(bytes[cursor + 2].toUByte().toInt(), 14).toUByte()
                        kind == 4 && optionLength == 2 -> sackPermitted = true
                    }
                    cursor += optionLength
                }
            }
        }
        return TcpOptions(maximumSegmentSize, windowScale, sackPermitted)
    }
}
