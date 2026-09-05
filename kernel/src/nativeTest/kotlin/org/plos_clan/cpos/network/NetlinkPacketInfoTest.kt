package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.SocketControlMessage
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NetlinkPacketInfoTest {
    @Test
    fun packetInfoIsDisabledByDefault() {
        assertFalse(NetlinkSocketOptions().packetInfo)
    }

    @Test
    fun encodesUnicastAndFullMulticastGroupNumbers() {
        for (group in listOf(0u, 1u, 4u, 32u, 33u, 65u, UInt.MAX_VALUE)) {
            val message = packetInfo(group)
            val bytes = ByteArray(message.space)

            assertEquals(24, message.writeTo(bytes, 0))
            val input = LittleEndianBuffer(bytes)
            assertEquals(20uL, input.readU64(0))
            assertEquals(270u, input.readU32(8))
            assertEquals(3u, input.readU32(12))
            assertEquals(group, input.readU32(16))
            assertContentEquals(ByteArray(4), bytes.copyOfRange(20, 24))
        }
    }

    @Test
    fun preservesFullPayloadWithoutRequiringTrailingPadding() {
        val message = packetInfo(65u)
        for (capacity in message.length..message.space) {
            val bytes = ByteArray(capacity)

            assertEquals(capacity, message.writeTo(bytes, 0))
            assertEquals(20uL, LittleEndianBuffer(bytes).readU64(0))
            assertEquals(65u, LittleEndianBuffer(bytes).readU32(16))
        }
    }

    @Test
    fun truncatesPayloadToAvailableCapacity() {
        val message = packetInfo(0x12345678u)
        val payload = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        for (capacity in SocketControlMessage.HEADER_SIZE until message.length) {
            val bytes = ByteArray(capacity)

            assertEquals(capacity, message.writeTo(bytes, 0))
            assertEquals(capacity.toULong(), LittleEndianBuffer(bytes).readU64(0))
            assertEquals(270u, LittleEndianBuffer(bytes).readU32(8))
            assertEquals(3u, LittleEndianBuffer(bytes).readU32(12))
            assertContentEquals(
                payload.copyOf(capacity - SocketControlMessage.HEADER_SIZE),
                bytes.copyOfRange(SocketControlMessage.HEADER_SIZE, capacity),
            )
        }
    }

    @Test
    fun leavesShortHeadersUntouched() {
        val message = packetInfo(65u)
        for (capacity in 0 until SocketControlMessage.HEADER_SIZE) {
            val bytes = ByteArray(capacity) { 0x5a }

            assertEquals(0, message.writeTo(bytes, 0))
            assertContentEquals(ByteArray(capacity) { 0x5a }, bytes)
        }
    }

    @Test
    fun appendsControlMessagesAtAlignedOffsets() {
        val first = packetInfo(0u)
        val second = packetInfo(65u)
        val bytes = ByteArray(first.space + second.space)
        val offset = first.writeTo(bytes, 0)

        assertEquals(24, offset)
        assertEquals(24, second.writeTo(bytes, offset))
        val input = LittleEndianBuffer(bytes)
        assertEquals(20uL, input.readU64(0))
        assertEquals(0u, input.readU32(16))
        assertEquals(20uL, input.readU64(offset))
        assertEquals(270u, input.readU32(offset + 8))
        assertEquals(3u, input.readU32(offset + 12))
        assertEquals(65u, input.readU32(offset + 16))
    }

    @Test
    fun combinesPacketInfoWithCredentialsInAnUnpaddedFinalMessage() {
        val packetInfo = packetInfo(65u)
        val credentials = SocketControlMessage.Integers(1, 2, intArrayOf(42, 1000, 1001))
        val bytes = ByteArray(packetInfo.space + credentials.length)
        val offset = packetInfo.writeTo(bytes, 0)

        assertEquals(28, credentials.writeTo(bytes, offset))
        val input = LittleEndianBuffer(bytes)
        assertEquals(28uL, input.readU64(offset))
        assertEquals(1u, input.readU32(offset + 8))
        assertEquals(2u, input.readU32(offset + 12))
        assertEquals(42u, input.readU32(offset + 16))
        assertEquals(1000u, input.readU32(offset + 20))
        assertEquals(1001u, input.readU32(offset + 24))
    }

    @Test
    fun truncatesAcrossIntegerBoundaries() {
        val message = SocketControlMessage.Integers(1, 2, intArrayOf(0x12345678, -1, 0x1234))
        val complete = ByteArray(message.length)
        message.writeTo(complete, 0)
        for (capacity in SocketControlMessage.HEADER_SIZE until message.length) {
            val bytes = ByteArray(capacity)

            assertEquals(capacity, message.writeTo(bytes, 0))
            assertEquals(capacity.toULong(), LittleEndianBuffer(bytes).readU64(0))
            assertContentEquals(
                complete.copyOfRange(SocketControlMessage.HEADER_SIZE, capacity),
                bytes.copyOfRange(SocketControlMessage.HEADER_SIZE, capacity),
            )
        }
    }

    private fun packetInfo(group: UInt): SocketControlMessage = SocketControlMessage.Integers(
        NetlinkAbi.SOL_NETLINK,
        NetlinkAbi.NETLINK_PKTINFO,
        intArrayOf(group.toInt()),
    )
}
