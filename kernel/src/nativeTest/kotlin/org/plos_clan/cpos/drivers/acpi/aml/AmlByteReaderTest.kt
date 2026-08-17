package org.plos_clan.cpos.drivers.acpi.aml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmlByteReaderTest {
    @Test
    fun readsLittleEndianIntegers() {
        val reader = readerOf(0x34, 0x12, 0x78, 0x56, 0x34, 0x12)

        assertEquals(0x1234u, reader.readU16())
        assertEquals(0x1234_5678u, reader.readU32())
        assertTrue(reader.exhausted)
        assertEquals(0, reader.remaining)
    }

    @Test
    fun preservesPositionWhenReadExceedsBounds() {
        val reader = readerOf(1, 2, 3)

        assertNull(reader.readU32())
        assertFalse(reader.skip(4))
        assertEquals(0, reader.position)
        assertTrue(reader.seek(3))
        assertTrue(reader.exhausted)
        assertNull(reader.readU8())
    }

    @Test
    fun decodesPackageLength() {
        val reader = readerOf(4, 0xAA, 0xBB, 0xCC)

        assertEquals(
            AmlPackageLength(
                encodedSize = 1,
                totalLength = 4,
                contentStart = 1,
                end = 4,
            ),
            reader.readPackageLength(),
        )
        assertEquals(1, reader.position)
    }

    private fun readerOf(vararg bytes: Int): AmlByteReader =
        AmlByteReader(AmlArraySource(ByteArray(bytes.size) { bytes[it].toByte() }))
}
