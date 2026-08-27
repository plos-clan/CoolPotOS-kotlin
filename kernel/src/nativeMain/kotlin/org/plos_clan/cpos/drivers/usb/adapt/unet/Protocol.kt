@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.unet

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.drivers.net.EthernetDevice
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.readU32
import platform.posix.memcpy
import platform.posix.memset

internal enum class RndisMessageType(val value: UInt) {
    PACKET(0x0000_0001u),
    INITIALIZE(0x0000_0002u),
    HALT(0x0000_0003u),
    QUERY(0x0000_0004u),
    SET(0x0000_0005u),
    RESET(0x0000_0006u),
    INDICATE_STATUS(0x0000_0007u),
    KEEP_ALIVE(0x0000_0008u),
    INITIALIZE_COMPLETE(0x8000_0002u),
    QUERY_COMPLETE(0x8000_0004u),
    SET_COMPLETE(0x8000_0005u),
    RESET_COMPLETE(0x8000_0006u),
    KEEP_ALIVE_COMPLETE(0x8000_0008u),
    ;

    companion object {
        fun from(value: UInt): RndisMessageType? = entries.firstOrNull { it.value == value }
    }
}

internal enum class RndisOid(val value: UInt) {
    SUPPORTED_LIST(0x0001_0101u),
    MAXIMUM_FRAME_SIZE(0x0001_0106u),
    LINK_SPEED(0x0001_0107u),
    CURRENT_PACKET_FILTER(0x0001_010eu),
    MAXIMUM_TOTAL_SIZE(0x0001_0111u),
    MEDIA_CONNECT_STATUS(0x0001_0114u),
    PERMANENT_ADDRESS(0x0101_0101u),
    CURRENT_ADDRESS(0x0101_0102u),
}

internal object RndisStatus {
    const val SUCCESS = 0x0000_0000u
    const val MEDIA_CONNECT = 0x4001_000bu
    const val MEDIA_DISCONNECT = 0x4001_000cu
}

internal object RndisPacketFilter {
    private const val DIRECTED = 0x0000_0001u
    private const val ALL_MULTICAST = 0x0000_0004u
    private const val BROADCAST = 0x0000_0008u

    val DEFAULT = DIRECTED or ALL_MULTICAST or BROADCAST
}

internal sealed class RndisCommand(
    val type: RndisMessageType,
    val expectedResponse: RndisMessageType?,
    open val requestId: UInt?,
) {
    abstract fun encode(): ByteArray

    fun accepts(response: RndisResponse): Boolean = response.type == expectedResponse &&
        (requestId == null || response.requestId == requestId)

    class Initialize(
        override val requestId: UInt,
        private val maximumTransferSize: UInt,
    ) : RndisCommand(RndisMessageType.INITIALIZE, RndisMessageType.INITIALIZE_COMPLETE, requestId) {
        override fun encode(): ByteArray = message(24) {
            writeU32(8, requestId)
            writeU32(12, 1u)
            writeU32(16, 0u)
            writeU32(20, maximumTransferSize)
        }
    }

    class Query(
        override val requestId: UInt,
        private val oid: UInt,
        private val information: ByteArray = ByteArray(0),
    ) : RndisCommand(RndisMessageType.QUERY, RndisMessageType.QUERY_COMPLETE, requestId) {
        override fun encode(): ByteArray = message(QUERY_HEADER_SIZE + information.size) {
            writeU32(8, requestId)
            writeU32(12, oid)
            writeU32(16, information.size.toUInt())
            writeU32(20, if (information.isEmpty()) 0u else QUERY_HEADER_SIZE.toUInt() - 8u)
        }.also { information.copyInto(it, QUERY_HEADER_SIZE) }
    }

    class Set(
        override val requestId: UInt,
        private val oid: UInt,
        private val information: ByteArray,
    ) : RndisCommand(RndisMessageType.SET, RndisMessageType.SET_COMPLETE, requestId) {
        override fun encode(): ByteArray = message(QUERY_HEADER_SIZE + information.size) {
            writeU32(8, requestId)
            writeU32(12, oid)
            writeU32(16, information.size.toUInt())
            writeU32(20, if (information.isEmpty()) 0u else QUERY_HEADER_SIZE.toUInt() - 8u)
        }.also { information.copyInto(it, QUERY_HEADER_SIZE) }
    }

    data object Reset :
        RndisCommand(RndisMessageType.RESET, RndisMessageType.RESET_COMPLETE, null) {
        override fun encode(): ByteArray = message(12) {}
    }

    class KeepAlive(override val requestId: UInt) : RndisCommand(
        RndisMessageType.KEEP_ALIVE,
        RndisMessageType.KEEP_ALIVE_COMPLETE,
        requestId,
    ) {
        override fun encode(): ByteArray = message(12) { writeU32(8, requestId) }
    }

    class Halt(override val requestId: UInt) :
        RndisCommand(RndisMessageType.HALT, null, requestId) {
        override fun encode(): ByteArray = message(12) { writeU32(8, requestId) }
    }

    class KeepAliveComplete(override val requestId: UInt) :
        RndisCommand(RndisMessageType.KEEP_ALIVE_COMPLETE, null, requestId) {
        override fun encode(): ByteArray = message(16) {
            writeU32(8, requestId)
            writeU32(12, RndisStatus.SUCCESS)
        }
    }

    protected fun message(size: Int, body: LittleEndianBuffer.() -> Unit): ByteArray =
        ByteArray(size).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, type.value)
                writeU32(4, size.toUInt())
                body()
            }
        }

    private companion object {
        const val QUERY_HEADER_SIZE = 28
    }
}

internal sealed class RndisResponse(
    val type: RndisMessageType,
    open val requestId: UInt?,
    open val status: UInt,
) {
    data class Initialize(
        override val requestId: UInt,
        override val status: UInt,
        val majorVersion: UInt,
        val minorVersion: UInt,
        val deviceFlags: UInt,
        val medium: UInt,
        val maximumPacketsPerTransfer: UInt,
        val maximumTransferSize: UInt,
        val packetAlignmentFactor: UInt,
    ) : RndisResponse(RndisMessageType.INITIALIZE_COMPLETE, requestId, status)

    data class Query(
        override val requestId: UInt,
        override val status: UInt,
        val information: ByteArray,
    ) : RndisResponse(RndisMessageType.QUERY_COMPLETE, requestId, status)

    data class Set(
        override val requestId: UInt,
        override val status: UInt,
    ) : RndisResponse(RndisMessageType.SET_COMPLETE, requestId, status)

    data class Reset(
        override val status: UInt,
        val addressingReset: Boolean,
    ) : RndisResponse(RndisMessageType.RESET_COMPLETE, null, status)

    data class KeepAlive(
        override val requestId: UInt,
        override val status: UInt,
    ) : RndisResponse(RndisMessageType.KEEP_ALIVE_COMPLETE, requestId, status)

    data class IndicateStatus(
        override val status: UInt,
        val information: ByteArray,
    ) : RndisResponse(RndisMessageType.INDICATE_STATUS, null, status)

    data class DeviceKeepAlive(override val requestId: UInt) :
        RndisResponse(RndisMessageType.KEEP_ALIVE, requestId, RndisStatus.SUCCESS)
}

internal data class RndisTransferParameters(
    val transferSize: UInt,
    val alignmentFactor: UInt,
    val packetHeaderSize: UInt,
) {
    fun maximumFrameSize(
        maximumPayload: UInt,
        maximumTotal: UInt,
        bufferCapacity: ULong,
    ): UInt? {
        if (bufferCapacity < packetHeaderSize.toULong()) return null
        val payloadLimit = maximumPayload.toULong() + EthernetDevice.HEADER_SIZE.toULong()
        val totalLimit = if (maximumTotal.toULong() > payloadLimit &&
            maximumTotal > packetHeaderSize
        ) {
            maximumTotal.toULong() - packetHeaderSize.toULong()
        } else {
            maximumTotal.toULong()
        }
        return minOf(
            transferSize.toULong() - packetHeaderSize.toULong(),
            payloadLimit,
            totalLimit,
            bufferCapacity - packetHeaderSize.toULong(),
        ).takeIf { it >= EthernetDevice.HEADER_SIZE.toULong() }?.toUInt()
    }

    companion object {
        fun negotiate(response: RndisResponse.Initialize, hostCapacity: UInt): RndisTransferParameters? {
            if (response.status != RndisStatus.SUCCESS ||
                response.majorVersion != RNDIS_MAJOR_VERSION ||
                response.minorVersion > RNDIS_MINOR_VERSION ||
                response.deviceFlags and DEVICE_FLAG_CONNECTIONLESS == 0u ||
                response.medium != MEDIUM_802_3 || response.maximumPacketsPerTransfer == 0u ||
                response.packetAlignmentFactor > MAX_PACKET_ALIGNMENT_FACTOR
            ) return null

            val packetHeaderSize = RndisPacketCodec.headerSize(response.packetAlignmentFactor)
                ?: return null
            if (response.maximumTransferSize > hostCapacity ||
                response.maximumTransferSize < packetHeaderSize + EthernetDevice.HEADER_SIZE.toUInt()
            ) return null
            return RndisTransferParameters(
                response.maximumTransferSize,
                response.packetAlignmentFactor,
                packetHeaderSize,
            )
        }

        private const val RNDIS_MAJOR_VERSION = 1u
        private const val RNDIS_MINOR_VERSION = 0u
        private const val DEVICE_FLAG_CONNECTIONLESS = 1u
        private const val MEDIUM_802_3 = 0u
        private const val MAX_PACKET_ALIGNMENT_FACTOR = 7u
    }
}

internal sealed interface RndisParseResult {
    data object Empty : RndisParseResult
    data class Valid(val response: RndisResponse) : RndisParseResult
    data class Invalid(val reason: String) : RndisParseResult
}

internal object RndisControlCodec {
    fun decode(bytes: ByteArray): RndisParseResult {
        if (bytes.size < HEADER_SIZE) return RndisParseResult.Invalid("truncated header")
        val fields = LittleEndianBuffer(bytes)
        val rawType = fields.readU32(0)
        if (rawType == 0u) return RndisParseResult.Empty
        val type = RndisMessageType.from(rawType)
            ?: return RndisParseResult.Invalid("unknown message type 0x${rawType.toString(16)}")
        val rawLength = fields.readU32(4)
        if (rawLength > Int.MAX_VALUE.toUInt()) {
            return RndisParseResult.Invalid("message length exceeds host range")
        }
        val length = rawLength.toInt()
        if (length < HEADER_SIZE || length > bytes.size) {
            return RndisParseResult.Invalid("invalid message length $length")
        }

        fun requireSize(minimum: Int): RndisParseResult.Invalid? =
            if (length < minimum) RndisParseResult.Invalid("truncated $type response") else null

        fun information(lengthOffset: Int, offsetOffset: Int): ByteArray? {
            val informationLength = fields.readU32(lengthOffset).toULong()
            val informationOffset = fields.readU32(offsetOffset).toULong()
            if (informationLength == 0uL) return ByteArray(0)
            val start = 8uL + informationOffset
            val end = start + informationLength
            if (end < start || start > length.toULong() || end > length.toULong()) return null
            return bytes.copyOfRange(start.toInt(), end.toInt())
        }

        return when (type) {
            RndisMessageType.INITIALIZE_COMPLETE -> requireSize(52) ?: RndisParseResult.Valid(
                RndisResponse.Initialize(
                    requestId = fields.readU32(8),
                    status = fields.readU32(12),
                    majorVersion = fields.readU32(16),
                    minorVersion = fields.readU32(20),
                    deviceFlags = fields.readU32(24),
                    medium = fields.readU32(28),
                    maximumPacketsPerTransfer = fields.readU32(32),
                    maximumTransferSize = fields.readU32(36),
                    packetAlignmentFactor = fields.readU32(40),
                ),
            )

            RndisMessageType.QUERY_COMPLETE -> requireSize(24) ?: run {
                val information = information(16, 20)
                    ?: return RndisParseResult.Invalid("query information exceeds message")
                RndisParseResult.Valid(
                    RndisResponse.Query(fields.readU32(8), fields.readU32(12), information),
                )
            }

            RndisMessageType.SET_COMPLETE -> requireSize(16) ?: RndisParseResult.Valid(
                RndisResponse.Set(fields.readU32(8), fields.readU32(12)),
            )

            RndisMessageType.RESET_COMPLETE -> requireSize(16) ?: RndisParseResult.Valid(
                RndisResponse.Reset(fields.readU32(8), fields.readU32(12) != 0u),
            )

            RndisMessageType.KEEP_ALIVE_COMPLETE -> requireSize(16) ?: RndisParseResult.Valid(
                RndisResponse.KeepAlive(fields.readU32(8), fields.readU32(12)),
            )

            RndisMessageType.INDICATE_STATUS -> requireSize(20) ?: run {
                val information = information(12, 16)
                    ?: return RndisParseResult.Invalid("status information exceeds message")
                RndisParseResult.Valid(RndisResponse.IndicateStatus(fields.readU32(8), information))
            }

            RndisMessageType.KEEP_ALIVE -> requireSize(12) ?: RndisParseResult.Valid(
                RndisResponse.DeviceKeepAlive(fields.readU32(8)),
            )

            else -> RndisParseResult.Invalid("unexpected control message $type")
        }
    }

    private const val HEADER_SIZE = 8
}

internal object RndisPacketCodec {
    const val HEADER_SIZE = 44

    fun headerSize(alignmentFactor: UInt): UInt? {
        if (alignmentFactor > MAX_ALIGNMENT_FACTOR) return null
        val alignment = 1u shl alignmentFactor.toInt()
        return ((HEADER_SIZE.toUInt() + alignment - 1u) / alignment) * alignment
    }

    fun encode(
        destination: CPointer<UByteVar>,
        capacity: UInt,
        frame: ByteArray,
        alignmentFactor: UInt,
    ): UInt? {
        if (frame.isEmpty()) return null
        val dataStart = headerSize(alignmentFactor) ?: return null
        val messageLength = dataStart.toULong() + frame.size.toULong()
        if (messageLength > capacity.toULong() || messageLength > UInt.MAX_VALUE.toULong()) return null

        memset(destination, 0, dataStart.toULong())
        destination.writeU32(0, RndisMessageType.PACKET.value)
        destination.writeU32(4, messageLength.toUInt())
        destination.writeU32(8, dataStart - 8u)
        destination.writeU32(12, frame.size.toUInt())
        frame.usePinned { bytes ->
            memcpy(
                requireNotNull(destination + dataStart.toInt()),
                bytes.addressOf(0),
                frame.size.toULong(),
            )
        }
        return messageLength.toUInt()
    }

    fun consume(
        source: CPointer<UByteVar>,
        length: UInt,
        maximumFrameSize: UInt,
        receiver: (CPointer<UByteVar>, UInt) -> Unit,
    ): Boolean {
        var offset = 0u
        while (offset < length) {
            val remaining = length - offset
            if (remaining < HEADER_SIZE.toUInt()) {
                return (0 until remaining.toInt()).all { source[offset.toInt() + it] == 0u.toUByte() }
            }

            val message = requireNotNull(source + offset.toInt())
            val type = message.readU32(0)
            if (type == 0u) return true
            val messageLength = message.readU32(4)
            val dataOffset = message.readU32(8)
            val dataLength = message.readU32(12)
            val dataStart = 8uL + dataOffset.toULong()
            val dataEnd = dataStart + dataLength.toULong()

            if (type != RndisMessageType.PACKET.value ||
                messageLength < HEADER_SIZE.toUInt() || messageLength > remaining ||
                dataStart < HEADER_SIZE.toULong() || dataEnd < dataStart ||
                dataEnd > messageLength.toULong() || dataLength > maximumFrameSize
            ) return false

            receiver(requireNotNull(message + dataStart.toInt()), dataLength)
            offset += messageLength
        }
        return true
    }

    private fun CPointer<UByteVar>.writeU32(offset: Int, value: UInt) {
        this[offset] = value.toUByte()
        this[offset + 1] = (value shr 8).toUByte()
        this[offset + 2] = (value shr 16).toUByte()
        this[offset + 3] = (value shr 24).toUByte()
    }

    private const val MAX_ALIGNMENT_FACTOR = 7u
}
