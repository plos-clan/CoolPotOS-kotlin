package org.plos_clan.cpos.drivers.scsi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.block.BlockOperation
import org.plos_clan.cpos.block.BlockStatus
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

class DiskTest {
    private class Transport : ScsiTransport() {
        override val maximumTransferBytes = 4096
        val commands = mutableListOf<ByteArray>()
        var lastBlock = 8191uL
        var blockSize = 512
        var readOnly = false
        var shortRead = false
        var status = ScsiStatus.GOOD
        var unitAttention = false
        var extendedMode = false

        override suspend fun execute(
            lun: ULong,
            command: ByteArray,
            direction: ScsiDirection,
            buffer: IoBuffer?,
            offset: Int,
            length: Int,
        ): ScsiResult {
            commands.add(command.copyOf())
            if (unitAttention) {
                unitAttention = false
                return ScsiResult(ScsiStatus.CHECK_CONDITION, sense = ScsiSense(6, 0x29, 0))
            }
            val data = ByteArray(length)
            val fields = BigEndianBuffer(data)
            when (command[0].toUByte().toInt()) {
                0x12 -> data[4] = 31
                0x25 -> {
                    fields.write(0, 4, minOf(lastBlock, UInt.MAX_VALUE.toULong()))
                    fields.write(4, 4, blockSize.toULong())
                }
                0x9e -> {
                    fields.write(0, 8, lastBlock)
                    fields.write(8, 4, blockSize.toULong())
                }
                0x1a -> {
                    if (extendedMode)
                        return ScsiResult(ScsiStatus.CHECK_CONDITION, sense = ScsiSense(5, 0x20, 0))
                    if (readOnly) data[2] = 0x80.toByte()
                }
                0x5a -> if (readOnly) data[3] = 0x80.toByte()
                0x28,
                0x88,
                0x2a,
                0x8a,
                0x35 -> {
                    return ScsiResult(status, if (shortRead) length / 2 else length)
                }
                0xa0 -> {
                    fields.write(0, 4, 16uL)
                    if (length >= 24) {
                        fields.write(8, 8, 0uL)
                        fields.write(16, 8, 0x4001000000000000uL)
                    }
                }
            }
            if (direction == ScsiDirection.IN) buffer?.copyFrom(offset, data, 0, length)
            return ScsiResult(ScsiStatus.GOOD, length)
        }
    }

    @Test
    fun capacityAndReadOnlyAreDiscovered() = runBlocking {
        val transport = Transport().also { it.readOnly = true }
        val disk = assertNotNull(ScsiDisk.probe(transport, 0uL))
        assertEquals(8192uL, disk.geometry.blockCount)
        assertEquals(512, disk.geometry.blockSize)
        assertEquals(
            BlockStatus.READ_ONLY,
            disk.transfer(BlockOperation.WRITE, 0uL, ByteArrayBuffer(ByteArray(512)), 0, 512).status,
        )
    }

    @Test
    fun discoveryClearsUnitAttentionAndFallsBackToModeSense10() = runBlocking {
        val transport =
            Transport().also {
                it.unitAttention = true
                it.extendedMode = true
                it.readOnly = true
            }
        val disk = assertNotNull(ScsiDisk.probe(transport, 0uL))
        assertEquals(true, disk.readOnly)
        assertEquals(2, transport.commands.count { it[0] == 0x12.toByte() })
        assertEquals(0x5a.toByte(), transport.commands.last()[0])
    }

    @Test
    fun extendedCapacityAndAddressingDoNotTruncate() = runBlocking {
        val transport = Transport().also { it.lastBlock = 0x100000010uL }
        val disk = assertNotNull(ScsiDisk.probe(transport, 0uL))
        assertEquals(0x100000011uL, disk.geometry.blockCount)
        val result =
            disk.transfer(
                BlockOperation.READ,
                0xffffffffuL,
                ByteArrayBuffer(ByteArray(1024)),
                0,
                1024,
            )
        assertEquals(BlockStatus.SUCCESS, result.status)
        val command = transport.commands.last()
        assertEquals(0x88.toByte(), command[0])
        assertEquals(0xffffffffuL, BigEndianBuffer(command).read(2, 8))
        assertEquals(2uL, BigEndianBuffer(command).read(10, 4))
    }

    @Test
    fun transportLimitsSplitRequestsAndShortReadsFail() = runBlocking {
        val transport = Transport()
        val disk = assertNotNull(ScsiDisk.probe(transport, 0uL))
        transport.commands.clear()
        val buffer = ByteArrayBuffer(ByteArray(8192))
        assertEquals(8192, disk.transfer(BlockOperation.READ, 0uL, buffer, 0, 8192).bytes)
        assertEquals(2, transport.commands.size)
        assertEquals(8uL, BigEndianBuffer(transport.commands.last()).read(2, 4))
        transport.shortRead = true
        assertEquals(
            BlockStatus.IO_ERROR,
            disk.transfer(BlockOperation.READ, 0uL, buffer, 0, 512).status,
        )
    }

    @Test
    fun flushErrorsAreNotReportedAsDurableWrites() = runBlocking {
        val transport = Transport()
        val disk = assertNotNull(ScsiDisk.probe(transport, 0uL))
        transport.status = ScsiStatus.IO_ERROR
        assertEquals(BlockStatus.IO_ERROR, disk.flush())
        assertEquals(0x35.toByte(), transport.commands.last()[0])
    }

    @Test
    fun reportLunsPreservesFullAddressAndRetriesAllocation() = runBlocking {
        val transport = Transport()
        assertEquals(listOf(0uL, 0x4001000000000000uL), transport.logicalUnits())
        assertEquals(listOf(16uL, 24uL), transport.commands.map { BigEndianBuffer(it).read(6, 4) })
    }

    @Test
    fun invalidCapacitiesAndTruncatedSenseAreRejected() = runBlocking {
        assertNull(ScsiDisk.probe(Transport().also { it.blockSize = 0 }, 0uL))
        assertNull(ScsiDisk.probe(Transport().also { it.lastBlock = ULong.MAX_VALUE }, 0uL))
        assertNull(ScsiSense.parse(byteArrayOf(0x70, 0, 7)))
        assertEquals(
            ScsiSense(7, 0x27, 0),
            ScsiSense.parse(byteArrayOf(0x72, 7, 0x27, 0, 0, 0, 0, 0)),
        )
        val fixed =
            ByteArray(18).also {
                it[0] = 0x70
                it[2] = 2
                it[7] = 10
                it[12] = 0x3a
            }
        assertEquals(ScsiSense(2, 0x3a, 0), ScsiSense.parse(fixed))
    }
}
