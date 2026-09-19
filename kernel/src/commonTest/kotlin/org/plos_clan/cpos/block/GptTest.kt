package org.plos_clan.cpos.block

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.mem.IoBuffer

class GptTest {
    private class Disk(val sector: Int = 512) : BlockDevice() {
        override val geometry = BlockGeometry(sector, 256uL)
        val data = ByteArray(sector * 256)

        init {
            (
                "4546492050415254000001005c00000059968e7b000000000100000000000000" +
                "ff000000000000002200000000000000fd0000000000000054e3c96bbd4cab4c" +
                "b0cb2f902f26d05f02000000000000000400000080000000f239784b"
            ).hexToByteArray().copyInto(data, sector)
            (
                "4546492050415254000001005c000000c8f6eb2c00000000ff00000000000000" +
                "01000000000000002200000000000000fd0000000000000054e3c96bbd4cab4c" +
                "b0cb2f902f26d05ffe000000000000000400000080000000f239784b"
            ).hexToByteArray().copyInto(data, sector * 255)
            val entries = ByteArray(512)
            (
                "af3dc60f838472478e793d69d8477de454e3c96bbd4cab4cb0cb2f902f26d05f" +
                "28000000000000004f000000000000000000000000000010"
            ).hexToByteArray().copyInto(entries)
            entries.copyInto(data, sector * 2)
            entries.copyInto(data, sector * 254)
        }

        override suspend fun transfer(
            operation: BlockOperation,
            block: ULong,
            buffer: IoBuffer,
            offset: Int,
            length: Int,
        ): BlockResult {
            check(operation == BlockOperation.READ)
            val source = block.toInt() * sector
            buffer.copyFrom(offset, data, source, length)
            return BlockResult(BlockStatus.SUCCESS, length)
        }

        override suspend fun flush() = BlockStatus.SUCCESS
    }

    @Test
    fun parsesGuidAndReadOnlyAttributeWithDifferentSectors() = runBlocking {
        for (sector in listOf(512, 4096)) {
            val partitions = assertNotNull(Gpt(Disk(sector)).read())
            assertEquals(1, partitions.size)
            val partition = partitions.single()
            assertEquals("6bc9e354-4cbd-4cab-b0cb-2f902f26d05f", partition.id.toString())
            assertEquals(40uL, partition.firstBlock)
            assertEquals(40uL, partition.blockCount)
            assertEquals(true, partition.readOnly)
        }
    }

    @Test
    fun recoversFromCorruptPrimaryHeader() = runBlocking {
        val disk = Disk()
        disk.data[512 + 16] = 0
        assertNotNull(Gpt(disk).read())
        Unit
    }

    @Test
    fun rejectsCorruptPartitionArrays() = runBlocking {
        val disk = Disk()
        disk.data[1024 + 32] = 0
        disk.data[254 * 512 + 32] = 0
        assertNull(Gpt(disk).read())
    }

    @Test
    fun rejectsMissingGpt() = runBlocking {
        val disk = Disk()
        disk.data.fill(0)
        assertNull(Gpt(disk).read())
    }
}
