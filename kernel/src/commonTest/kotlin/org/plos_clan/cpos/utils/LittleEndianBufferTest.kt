package org.plos_clan.cpos.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LittleEndianBufferTest {
    @Test
    fun readsEverySupportedIntegerWidth() {
        val input = LittleEndianBuffer(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val expected = listOf(
            0uL,
            0x01uL,
            0x0201uL,
            0x03_0201uL,
            0x0403_0201uL,
            0x05_0403_0201uL,
            0x0605_0403_0201uL,
            0x07_0605_0403_0201uL,
            0x0807_0605_0403_0201uL,
        )

        expected.forEachIndexed { width, value ->
            assertEquals(value, input.readUnsigned(0, width), "width=$width")
        }
        assertEquals(0x0201u.toUShort(), input.readU16(0))
        assertEquals(0x0403_0201u, input.readU32(0))
        assertEquals(0x0807_0605_0403_0201uL, input.readU64(0))
    }

    @Test
    fun writesIntegersInLittleEndianOrder() {
        val bytes = ByteArray(14)
        LittleEndianBuffer(bytes).apply {
            writeU16(0, 0x1234u)
            writeU32(2, 0x89AB_CDEFu)
            writeU64(6, 0x0123_4567_89AB_CDEFuL)
        }

        assertContentEquals(
            byteArrayOf(
                0x34, 0x12,
                0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte(),
                0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte(),
                0x67, 0x45, 0x23, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun rejectsInvalidWidthsAndRanges() {
        val input = LittleEndianBuffer(ByteArray(8))

        assertEquals(0uL, input.readUnsigned(8, 0))
        assertFailsWith<IllegalArgumentException> { input.readUnsigned(0, -1) }
        assertFailsWith<IllegalArgumentException> { input.readUnsigned(0, 9) }
        assertFailsWith<IllegalArgumentException> { input.readU8(-1) }
        assertFailsWith<IllegalArgumentException> { input.readU16(7) }
        assertFailsWith<IllegalArgumentException> { input.writeU64(1, 0uL) }
    }
}
