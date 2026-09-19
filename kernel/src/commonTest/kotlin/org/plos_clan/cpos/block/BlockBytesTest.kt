package org.plos_clan.cpos.block

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.IoBuffer

class BlockBytesTest {
    private class Disk(size: Int = 512, override val readOnly: Boolean = false) : BlockDevice() {
        override val geometry = BlockGeometry(size, 8uL)
        val data = ByteArray(geometry.byteSize.toInt()) { it.toByte() }
        val operations = mutableListOf<Pair<BlockOperation, ULong>>()
        var failure: ULong? = null

        override suspend fun transfer(
            operation: BlockOperation,
            block: ULong,
            buffer: IoBuffer,
            offset: Int,
            length: Int,
        ): BlockResult {
            assertEquals(0, length % geometry.blockSize)
            operations.add(operation to block)
            if (block == failure) return BlockResult(BlockStatus.IO_ERROR)
            val address = (block * geometry.blockSize.toUInt()).toInt()
            if (operation == BlockOperation.READ) buffer.copyFrom(offset, data, address, length)
            else buffer.copyTo(offset, data, address, length)
            return BlockResult(BlockStatus.SUCCESS, length)
        }

        override suspend fun flush() = BlockStatus.SUCCESS
    }

    @Test
    fun unalignedWritePreservesBothBoundaryBlocks() = runBlocking {
        val disk = Disk()
        val original = disk.data.copyOf()
        val input = ByteArray(1200) { 0x6a }
        val result =
            BlockBytes(disk).transfer(BlockOperation.WRITE, 23uL, ByteArrayBuffer(input), 17, 1100)
        assertEquals(BlockResult(BlockStatus.SUCCESS, 1100), result)
        input.copyInto(original, 23, 17, 1117)
        assertContentEquals(original, disk.data)
        assertEquals(
            listOf(
                BlockOperation.READ to 0uL,
                BlockOperation.WRITE to 0uL,
                BlockOperation.WRITE to 1uL,
                BlockOperation.READ to 2uL,
                BlockOperation.WRITE to 2uL,
            ),
            disk.operations,
        )
    }

    @Test
    fun largeAndNonPowerOfTwoBlocksSupportByteReads() = runBlocking {
        for (size in listOf(512, 4096, 520, 8192)) {
            val disk = Disk(size)
            val output = ByteArray(size * 3 + 7)
            val result =
                BlockBytes(disk)
                    .transfer(BlockOperation.READ, 19uL, ByteArrayBuffer(output), 3, size * 3)
            assertEquals(BlockResult(BlockStatus.SUCCESS, size * 3), result)
            assertContentEquals(
                disk.data.copyOfRange(19, 19 + size * 3),
                output.copyOfRange(3, 3 + size * 3),
            )
        }
    }

    @Test
    fun alignedIoIsNotSplitIntoIndividualSectors() = runBlocking {
        val disk = Disk()
        val result =
            BlockBytes(disk)
                .transfer(BlockOperation.READ, 512uL, ByteArrayBuffer(ByteArray(2048)), 0, 2048)
        assertEquals(2048, result.bytes)
        assertEquals(listOf(BlockOperation.READ to 1uL), disk.operations)
    }

    @Test
    fun endOfDeviceAndOverflowAreBounded() =
        runBlocking<Unit> {
            val disk = Disk()
            val access = BlockBytes(disk)
            val buffer = ByteArrayBuffer(ByteArray(1024))
            assertEquals(96, access.transfer(BlockOperation.READ, 4000uL, buffer, 0, 1024).bytes)
            assertEquals(
                0,
                access.transfer(BlockOperation.READ, ULong.MAX_VALUE, buffer, 0, 1024).bytes,
            )
            assertEquals(
                BlockStatus.INVALID,
                access.transfer(BlockOperation.READ, 0uL, buffer, Int.MAX_VALUE, 1).status,
            )
            assertFailsWith<IllegalArgumentException> { BlockGeometry(512, ULong.MAX_VALUE) }
        }

    @Test
    fun readOnlyAndFailedReadModifyWriteNeverSubmitWrites() = runBlocking {
        val buffer = ByteArrayBuffer(ByteArray(100))
        val readOnly = Disk(readOnly = true)
        assertEquals(
            BlockStatus.READ_ONLY,
            BlockBytes(readOnly).transfer(BlockOperation.WRITE, 1uL, buffer, 0, 100).status,
        )
        assertEquals(emptyList(), readOnly.operations)
        val failed = Disk().also { it.failure = 0uL }
        assertEquals(
            BlockStatus.IO_ERROR,
            BlockBytes(failed).transfer(BlockOperation.WRITE, 1uL, buffer, 0, 100).status,
        )
        assertEquals(listOf(BlockOperation.READ to 0uL), failed.operations)
    }
}
