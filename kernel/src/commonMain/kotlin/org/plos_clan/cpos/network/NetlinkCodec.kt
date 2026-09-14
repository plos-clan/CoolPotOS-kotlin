package org.plos_clan.cpos.network

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class NetlinkBuffer internal constructor(
    val bytes: ByteArray,
    val offset: Int = 0,
    val size: Int = bytes.size - offset,
) {
    init {
        require(offset >= 0 && size >= 0 && offset <= bytes.size - size)
    }

    fun readU8(offset: Int): UByte {
        requireRange(offset, UByte.SIZE_BYTES)
        return LittleEndianBuffer(bytes).readU8(this.offset + offset)
    }

    fun readU16(offset: Int): UShort {
        requireRange(offset, UShort.SIZE_BYTES)
        return LittleEndianBuffer(bytes).readU16(this.offset + offset)
    }

    fun readU32(offset: Int): UInt {
        requireRange(offset, UInt.SIZE_BYTES)
        return LittleEndianBuffer(bytes).readU32(this.offset + offset)
    }

    fun slice(offset: Int, size: Int = this.size - offset): NetlinkBuffer? =
        if (offset >= 0 && size >= 0 && offset <= this.size - size) {
            NetlinkBuffer(bytes, this.offset + offset, size)
        } else {
            null
        }

    fun copy(): ByteArray = bytes.copyOfRange(offset, offset + size)

    private fun requireRange(offset: Int, size: Int) {
        require(offset >= 0 && offset <= this.size - size)
    }
}

internal class NetlinkMessage internal constructor(
    val type: UShort,
    val flags: UShort,
    val sequence: UInt,
    val portId: UInt,
    val payload: NetlinkBuffer,
    internal val raw: NetlinkBuffer,
) {
    fun attributes(offset: Int = 0): NetlinkAttributes? = NetlinkAttributes.parse(payload, offset)
}

internal class NetlinkAttributeView internal constructor(
    val rawType: UShort,
    val payload: NetlinkBuffer,
    internal val offset: Int,
) {
    val type: Int
        get() = rawType.toInt() and NetlinkAbi.NLA_TYPE_MASK
    val nested: Boolean
        get() = rawType.toInt() and NetlinkAbi.NLA_F_NESTED != 0
    val networkByteOrder: Boolean
        get() = rawType.toInt() and NetlinkAbi.NLA_F_NET_BYTEORDER != 0

    fun u8(): UByte? = payload.takeIf { it.size == UByte.SIZE_BYTES }?.readU8(0)

    fun u16(): UShort? {
        if (payload.size != UShort.SIZE_BYTES) return null
        return if (networkByteOrder) NetworkOrderBuffer(payload.bytes).readU16(payload.offset)
        else payload.readU16(0)
    }

    fun u32(): UInt? {
        if (payload.size != UInt.SIZE_BYTES) return null
        return if (networkByteOrder) NetworkOrderBuffer(payload.bytes).readU32(payload.offset)
        else payload.readU32(0)
    }

    fun string(maximumSize: Int = Int.MAX_VALUE): String? {
        if (payload.size !in 1..maximumSize) return null
        val last = payload.offset + payload.size - 1
        if (payload.bytes[last] != 0.toByte()) return null
        for (index in payload.offset until last) {
            if (payload.bytes[index] == 0.toByte()) return null
        }
        return payload.bytes.decodeToString(payload.offset, last)
    }

    fun attributes(): NetlinkAttributes? = NetlinkAttributes.parse(payload)
}

internal class NetlinkAttributes private constructor(
    private val values: List<NetlinkAttributeView>,
) : Iterable<NetlinkAttributeView> {
    override fun iterator(): Iterator<NetlinkAttributeView> = values.iterator()

    operator fun get(type: Int): NetlinkAttributeView? = values.lastOrNull { it.type == type }

    fun all(type: Int): List<NetlinkAttributeView> = values.filter { it.type == type }

    companion object {
        internal fun parse(payload: NetlinkBuffer, offset: Int = 0): NetlinkAttributes? {
            if (offset < 0 || offset > payload.size) return null
            val attributes = mutableListOf<NetlinkAttributeView>()
            var cursor = offset
            while (cursor < payload.size) {
                val remaining = payload.size - cursor
                if (remaining < NetlinkCodec.ATTRIBUTE_HEADER_SIZE) return null
                val length = payload.readU16(cursor).toInt()
                if (length < NetlinkCodec.ATTRIBUTE_HEADER_SIZE || length > remaining) return null
                val headerOffset = payload.offset + cursor
                attributes += NetlinkAttributeView(
                    payload.readU16(cursor + UShort.SIZE_BYTES),
                    NetlinkBuffer(
                        payload.bytes,
                        headerOffset + NetlinkCodec.ATTRIBUTE_HEADER_SIZE,
                        length - NetlinkCodec.ATTRIBUTE_HEADER_SIZE,
                    ),
                    headerOffset,
                )
                val aligned = NetlinkCodec.align(length)
                if (aligned > remaining) {
                    if (length != remaining) return null
                    cursor = payload.size
                } else {
                    cursor += aligned
                }
            }
            return NetlinkAttributes(attributes)
        }
    }
}

internal sealed class NetlinkAttribute(
    val type: Int,
    private val flags: Int = 0,
) {
    init {
        require(type in 0..NetlinkAbi.NLA_TYPE_MASK)
        require(flags and (NetlinkAbi.NLA_F_NESTED or NetlinkAbi.NLA_F_NET_BYTEORDER).inv() == 0)
        require(flags != (NetlinkAbi.NLA_F_NESTED or NetlinkAbi.NLA_F_NET_BYTEORDER))
    }

    internal abstract val payloadSize: Int

    internal val encodedSize: Int
        get() = NetlinkCodec.align(NetlinkCodec.ATTRIBUTE_HEADER_SIZE + payloadSize)

    protected abstract fun writePayload(bytes: ByteArray, offset: Int)

    internal fun writeTo(bytes: ByteArray, offset: Int) {
        val length = NetlinkCodec.ATTRIBUTE_HEADER_SIZE + payloadSize
        require(length <= UShort.MAX_VALUE.toInt())
        LittleEndianBuffer(bytes).apply {
            writeU16(offset, length.toUShort())
            writeU16(offset + UShort.SIZE_BYTES, (type or flags).toUShort())
        }
        writePayload(bytes, offset + NetlinkCodec.ATTRIBUTE_HEADER_SIZE)
    }

    private class Binary(
        type: Int,
        private val value: ByteArray,
        flags: Int,
    ) : NetlinkAttribute(type, flags) {
        override val payloadSize = value.size

        override fun writePayload(bytes: ByteArray, offset: Int) {
            value.copyInto(bytes, offset)
        }
    }

    private class UInt8(type: Int, private val value: UByte) : NetlinkAttribute(type) {
        override val payloadSize = UByte.SIZE_BYTES

        override fun writePayload(bytes: ByteArray, offset: Int) {
            LittleEndianBuffer(bytes).writeU8(offset, value)
        }
    }

    private class UInt16(type: Int, private val value: UShort) : NetlinkAttribute(type) {
        override val payloadSize = UShort.SIZE_BYTES

        override fun writePayload(bytes: ByteArray, offset: Int) {
            LittleEndianBuffer(bytes).writeU16(offset, value)
        }
    }

    private class UInt32(type: Int, private val value: UInt) : NetlinkAttribute(type) {
        override val payloadSize = UInt.SIZE_BYTES

        override fun writePayload(bytes: ByteArray, offset: Int) {
            LittleEndianBuffer(bytes).writeU32(offset, value)
        }
    }

    private class Nested(
        type: Int,
        private val attributes: List<NetlinkAttribute>,
        marked: Boolean,
    ) : NetlinkAttribute(type, if (marked) NetlinkAbi.NLA_F_NESTED else 0) {
        override val payloadSize = attributes.sumOf(NetlinkAttribute::encodedSize)

        override fun writePayload(bytes: ByteArray, offset: Int) {
            var cursor = offset
            for (attribute in attributes) {
                attribute.writeTo(bytes, cursor)
                cursor += attribute.encodedSize
            }
        }
    }

    companion object {
        fun binary(type: Int, value: ByteArray): NetlinkAttribute = Binary(type, value, 0)

        fun networkBinary(type: Int, value: ByteArray): NetlinkAttribute =
            Binary(type, value, NetlinkAbi.NLA_F_NET_BYTEORDER)

        fun u8(type: Int, value: UByte): NetlinkAttribute = UInt8(type, value)

        fun u16(type: Int, value: UShort): NetlinkAttribute = UInt16(type, value)

        fun u32(type: Int, value: UInt): NetlinkAttribute = UInt32(type, value)

        fun string(type: Int, value: String): NetlinkAttribute {
            val encoded = value.encodeToByteArray()
            return Binary(type, ByteArray(encoded.size + 1).also { encoded.copyInto(it) }, 0)
        }

        fun nested(
            type: Int,
            attributes: List<NetlinkAttribute>,
            marked: Boolean = true,
        ): NetlinkAttribute = Nested(type, attributes, marked)
    }
}

internal object NetlinkCodec {
    const val HEADER_SIZE = 16
    const val ATTRIBUTE_HEADER_SIZE = 4

    fun decode(bytes: ByteArray): List<NetlinkMessage> {
        val input = LittleEndianBuffer(bytes)
        val messages = ArrayList<NetlinkMessage>(1)
        var offset = 0
        while (bytes.size - offset >= HEADER_SIZE) {
            val remaining = bytes.size - offset
            val encodedLength = input.readU32(offset)
            if (encodedLength < HEADER_SIZE.toUInt() || encodedLength > remaining.toUInt()) break
            val length = encodedLength.toInt()
            messages += NetlinkMessage(
                input.readU16(offset + 4),
                input.readU16(offset + 6),
                input.readU32(offset + 8),
                input.readU32(offset + 12),
                NetlinkBuffer(bytes, offset + HEADER_SIZE, length - HEADER_SIZE),
                NetlinkBuffer(bytes, offset, length),
            )
            offset += minOf(align(length), remaining)
        }
        return messages
    }

    fun encode(
        type: Int,
        flags: Int,
        sequence: UInt,
        portId: UInt = 0u,
        payload: ByteArray = EMPTY,
    ): ByteArray = ByteArray(HEADER_SIZE + payload.size).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU32(0, bytes.size.toUInt())
            writeU16(4, type.toUShort())
            writeU16(6, flags.toUShort())
            writeU32(8, sequence)
            writeU32(12, portId)
        }
        payload.copyInto(bytes, HEADER_SIZE)
    }

    fun payload(
        fixed: ByteArray = EMPTY,
        attributes: List<NetlinkAttribute>,
    ): ByteArray {
        val bytes = ByteArray(fixed.size + attributes.sumOf(NetlinkAttribute::encodedSize))
        fixed.copyInto(bytes)
        var offset = fixed.size
        for (attribute in attributes) {
            attribute.writeTo(bytes, offset)
            offset += attribute.encodedSize
        }
        return bytes
    }

    internal fun align(length: Int): Int = (length + ALIGNMENT - 1) and -ALIGNMENT

    private val EMPTY = ByteArray(0)
    private const val ALIGNMENT = 4
}

internal object NetlinkAbi {
    const val SOL_NETLINK = 270
    const val NLMSG_NOOP = 1
    const val NLMSG_ERROR = 2
    const val NLMSG_DONE = 3
    const val NLM_F_REQUEST = 0x0001
    const val NLM_F_MULTI = 0x0002
    const val NLM_F_ACK = 0x0004
    const val NLM_F_ECHO = 0x0008
    const val NLM_F_ROOT = 0x0100
    const val NLM_F_MATCH = 0x0200
    const val NLM_F_DUMP = NLM_F_ROOT or NLM_F_MATCH
    const val NLM_F_REPLACE = 0x0100
    const val NLM_F_CAPPED = 0x0100
    const val NLM_F_ACK_TLVS = 0x0200
    const val NLA_F_NESTED = 0x8000
    const val NLA_F_NET_BYTEORDER = 0x4000
    const val NLA_TYPE_MASK = 0x3FFF
    const val NETLINK_ADD_MEMBERSHIP = 1
    const val NETLINK_DROP_MEMBERSHIP = 2
    const val NETLINK_PKTINFO = 3
    const val NETLINK_BROADCAST_ERROR = 4
    const val NETLINK_NO_ENOBUFS = 5
    const val NETLINK_LIST_MEMBERSHIPS = 9
    const val NETLINK_CAP_ACK = 10
    const val NETLINK_EXT_ACK = 11
    const val NETLINK_GET_STRICT_CHK = 12
}
