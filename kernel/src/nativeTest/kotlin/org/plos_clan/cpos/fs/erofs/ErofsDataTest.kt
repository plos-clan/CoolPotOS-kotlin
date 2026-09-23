package org.plos_clan.cpos.fs.erofs

import org.plos_clan.cpos.block.ByteSource
import org.plos_clan.cpos.time.Instant
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErofsDataTest {
    private class Fixture(val length: Int) {
        private val bytes = ByteArray(14 * 4096) { (it * 31 + it / 4096).toByte() }
        private val source = object : ByteSource {
            override val size = bytes.size.toULong()
            override fun close() = Unit
            override fun read(position: ULong, destination: ByteArray): Boolean {
                if (position > size || destination.size.toULong() > size - position) return false
                val start = position.toInt()
                bytes.copyInto(destination, 0, start, start + destination.size)
                return true
            }
        }
        val image = Image(source)
        val header: Header
        val packed = FlatData(image, 4096, 0uL, 0, 11uL, 8192uL, false)
        val inode: DiskInode
        val expected: ByteArray

        init {
            val time = Instant(0, 0u)
            header = Header(4096, 0uL, 0uL, time, 0uL)
            bytes.fill(0, 0, 96)
            val fields = LittleEndianBuffer(bytes)
            fields.writeU16(0, 7u)
            fields.writeU16(4, 0x81a4u)
            fields.writeU64(8, length.toULong())
            fields.writeU32(16, 1u)
            fields.writeU32(44, 1u)
            fields.writeU32(64, 32u)
            fields.writeU16(68, 0x27u)
            fields.writeU8(70, 3u)
            fields.writeU16(72, 0u)
            fields.writeU16(74, 0x1000u)
            fields.writeU32(76, 10u)
            fields.writeU16(80, 0x1001u)
            inode = assertNotNull(DiskInode.read(image, header, 0uL))
            expected = bytes.copyOfRange(10 * 4096, 11 * 4096) +
                bytes.copyOfRange(11 * 4096 + 32, 11 * 4096 + 32 + length - 4096)
        }

        fun open(packed: FileData? = this.packed): CompressedData? {
            val cache = ExtentCache<Extent.Stored>(8192)
            return CompressedData.open(image, header, inode, cache, packed)
        }
    }

    @Test
    fun readsAcrossStoredAndPackedExtents() {
        val fixture = Fixture(5000)
        val file = assertNotNull(fixture.open())
        val actual = ByteArray(fixture.length)
        assertTrue(file.read(0uL, actual, 0, actual.size))
        assertContentEquals(fixture.expected, actual)
        val crossing = ByteArray(48)
        assertTrue(file.read(4080uL, crossing, 0, crossing.size))
        assertContentEquals(fixture.expected.copyOfRange(4080, 4128), crossing)
    }

    @Test
    fun eofIndexDoesNotTurnPackedTailIntoAnEmptyPhysicalCluster() {
        val fixture = Fixture(8193)
        val file = assertNotNull(fixture.open())
        val actual = ByteArray(fixture.length)
        assertTrue(file.read(0uL, actual, 0, actual.size))
        assertContentEquals(fixture.expected, actual)
    }

    @Test
    fun rejectsMissingOrTruncatedPackedDataAndOutOfRangeReads() {
        val fixture = Fixture(5000)
        assertNull(fixture.open(null))
        val truncated = FragmentData(fixture.packed, 0uL, 128uL)
        assertNull(fixture.open(truncated))
        val destination = ByteArray(2)
        assertFalse(truncated.read(127uL, destination, 0, destination.size))
    }
}
