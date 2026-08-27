@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.unet

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RndisProtocolTest {
    @Test
    fun encodesControlRequestsWithProtocolRelativeOffsets() {
        val initialize = LittleEndianBuffer(RndisCommand.Initialize(7u, 0x4000u).encode())
        assertEquals(RndisMessageType.INITIALIZE.value, initialize.readU32(0))
        assertEquals(24u, initialize.readU32(4))
        assertEquals(7u, initialize.readU32(8))
        assertEquals(1u, initialize.readU32(12))
        assertEquals(0x4000u, initialize.readU32(20))

        val information = byteArrayOf(1, 2, 3, 4)
        val setBytes = RndisCommand.Set(9u, RndisOid.CURRENT_PACKET_FILTER.value, information).encode()
        val set = LittleEndianBuffer(setBytes)
        assertEquals(32u, set.readU32(4))
        assertEquals(4u, set.readU32(16))
        assertEquals(20u, set.readU32(20))
        assertContentEquals(information, setBytes.copyOfRange(28, 32))
    }

    @Test
    fun decodesInitializeAndQueryCompletions() {
        val initialize = ByteArray(52)
        LittleEndianBuffer(initialize).apply {
            writeU32(0, RndisMessageType.INITIALIZE_COMPLETE.value)
            writeU32(4, initialize.size.toUInt())
            writeU32(8, 11u)
            writeU32(12, RndisStatus.SUCCESS)
            writeU32(16, 1u)
            writeU32(20, 0u)
            writeU32(24, 1u)
            writeU32(28, 0u)
            writeU32(32, 4u)
            writeU32(36, 0x4000u)
            writeU32(40, 3u)
        }
        val initialized = assertIs<RndisParseResult.Valid>(RndisControlCodec.decode(initialize))
            .response
        assertIs<RndisResponse.Initialize>(initialized)
        assertEquals(11u, initialized.requestId)
        assertEquals(4u, initialized.maximumPacketsPerTransfer)
        assertEquals(3u, initialized.packetAlignmentFactor)

        val payload = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56)
        val query = ByteArray(24 + payload.size)
        LittleEndianBuffer(query).apply {
            writeU32(0, RndisMessageType.QUERY_COMPLETE.value)
            writeU32(4, query.size.toUInt())
            writeU32(8, 12u)
            writeU32(12, RndisStatus.SUCCESS)
            writeU32(16, payload.size.toUInt())
            writeU32(20, 16u)
        }
        payload.copyInto(query, 24)
        val queried = assertIs<RndisResponse.Query>(
            assertIs<RndisParseResult.Valid>(RndisControlCodec.decode(query)).response,
        )
        assertEquals(12u, queried.requestId)
        assertContentEquals(payload, queried.information)
    }

    @Test
    fun negotiatesTransferLimitsWithoutDeviceSpecificSizing() {
        val response = RndisResponse.Initialize(
            requestId = 1u,
            status = RndisStatus.SUCCESS,
            majorVersion = 1u,
            minorVersion = 0u,
            deviceFlags = 1u,
            medium = 0u,
            maximumPacketsPerTransfer = 1u,
            maximumTransferSize = 1580u,
            packetAlignmentFactor = 0u,
        )
        val parameters = assertNotNull(RndisTransferParameters.negotiate(response, 0x4000u))
        assertEquals(1514u, parameters.maximumFrameSize(1514u, 1558u, 0x4000uL))
        assertTrue(RndisCommand.Initialize(3u, 0x4000u).accepts(response.copy(requestId = 3u)))
        assertFalse(RndisCommand.Initialize(4u, 0x4000u).accepts(response.copy(requestId = 3u)))
    }

    @Test
    fun rejectsOutOfBoundsControlPayload() {
        val query = ByteArray(24)
        LittleEndianBuffer(query).apply {
            writeU32(0, RndisMessageType.QUERY_COMPLETE.value)
            writeU32(4, query.size.toUInt())
            writeU32(12, RndisStatus.SUCCESS)
            writeU32(16, 8u)
            writeU32(20, UInt.MAX_VALUE)
        }
        assertIs<RndisParseResult.Invalid>(RndisControlCodec.decode(query))
    }

    @Test
    fun coversResetKeepAliveHaltAndStatusMessages() {
        val halt = LittleEndianBuffer(RndisCommand.Halt(21u).encode())
        assertEquals(RndisMessageType.HALT.value, halt.readU32(0))
        assertEquals(12u, halt.readU32(4))
        assertEquals(21u, halt.readU32(8))

        val reset = ByteArray(16)
        LittleEndianBuffer(reset).apply {
            writeU32(0, RndisMessageType.RESET_COMPLETE.value)
            writeU32(4, reset.size.toUInt())
            writeU32(8, RndisStatus.SUCCESS)
            writeU32(12, 1u)
        }
        assertTrue(
            assertIs<RndisResponse.Reset>(
                assertIs<RndisParseResult.Valid>(RndisControlCodec.decode(reset)).response,
            ).addressingReset,
        )

        val keepAlive = ByteArray(16)
        LittleEndianBuffer(keepAlive).apply {
            writeU32(0, RndisMessageType.KEEP_ALIVE_COMPLETE.value)
            writeU32(4, keepAlive.size.toUInt())
            writeU32(8, 22u)
            writeU32(12, RndisStatus.SUCCESS)
        }
        assertEquals(
            22u,
            assertIs<RndisResponse.KeepAlive>(
                assertIs<RndisParseResult.Valid>(RndisControlCodec.decode(keepAlive)).response,
            ).requestId,
        )

        val indication = ByteArray(20)
        LittleEndianBuffer(indication).apply {
            writeU32(0, RndisMessageType.INDICATE_STATUS.value)
            writeU32(4, indication.size.toUInt())
            writeU32(8, RndisStatus.MEDIA_DISCONNECT)
        }
        assertEquals(
            RndisStatus.MEDIA_DISCONNECT,
            assertIs<RndisResponse.IndicateStatus>(
                assertIs<RndisParseResult.Valid>(RndisControlCodec.decode(indication)).response,
            ).status,
        )
    }

    @Test
    fun framesPacketsWithDeviceAlignmentAndParsesBatches() {
        val firstFrame = ByteArray(64) { it.toByte() }
        val secondFrame = ByteArray(96) { (255 - it).toByte() }
        val first = encodePacket(firstFrame, 3u)
        val second = encodePacket(secondFrame, 3u)
        val transfer = first + second + ByteArray(7)
        val received = mutableListOf<ByteArray>()

        val valid = transfer.usePinned { bytes ->
            RndisPacketCodec.consume(
                bytes.addressOf(0).reinterpret(),
                transfer.size.toUInt(),
                1514u,
            ) { frame, length -> received += frame.readBytes(length.toInt()) }
        }
        assertTrue(valid)
        assertContentEquals(firstFrame, received[0])
        assertContentEquals(secondFrame, received[1])

        val header = LittleEndianBuffer(first)
        assertEquals(40u, header.readU32(8))
        assertEquals(48u + firstFrame.size.toUInt(), header.readU32(4))
    }

    @Test
    fun rejectsPacketWhoseDataEscapesItsMessage() {
        val packet = encodePacket(ByteArray(64), 0u)
        LittleEndianBuffer(packet).writeU32(12, 128u)
        val valid = packet.usePinned { bytes ->
            RndisPacketCodec.consume(
                bytes.addressOf(0).reinterpret(),
                packet.size.toUInt(),
                1514u,
            ) { _, _ -> }
        }
        assertFalse(valid)
    }

    private fun encodePacket(frame: ByteArray, alignmentFactor: UInt): ByteArray {
        val buffer = ByteArray(2048)
        val length = buffer.usePinned { bytes ->
            RndisPacketCodec.encode(
                bytes.addressOf(0).reinterpret(),
                buffer.size.toUInt(),
                frame,
                alignmentFactor,
            )
        }
        return buffer.copyOf(assertNotNull(length).toInt())
    }
}
