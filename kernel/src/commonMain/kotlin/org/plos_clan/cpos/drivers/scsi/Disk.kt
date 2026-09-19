package org.plos_clan.cpos.drivers.scsi

import kotlinx.coroutines.delay
import org.plos_clan.cpos.block.BlockDevice
import org.plos_clan.cpos.block.BlockGeometry
import org.plos_clan.cpos.block.BlockOperation
import org.plos_clan.cpos.block.BlockResult
import org.plos_clan.cpos.block.BlockStatus
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

class ScsiDisk
private constructor(
    private val transport: ScsiTransport,
    val lun: ULong,
    override val geometry: BlockGeometry,
    override val readOnly: Boolean,
    val model: String,
) : BlockDevice() {
    override val connected: Boolean
        get() = transport.connected

    override suspend fun transfer(
        operation: BlockOperation,
        block: ULong,
        buffer: IoBuffer,
        offset: Int,
        length: Int,
    ): BlockResult {
        val size = geometry.blockSize
        if (offset < 0 || length < 0 || offset > Int.MAX_VALUE - length || length % size != 0) {
            return BlockResult(BlockStatus.INVALID)
        }
        val blocks = (length / size).toULong()
        if (block > geometry.blockCount || blocks > geometry.blockCount - block) {
            return BlockResult(BlockStatus.INVALID)
        }
        if (operation == BlockOperation.WRITE && readOnly) return BlockResult(BlockStatus.READ_ONLY)
        val direction =
            if (operation == BlockOperation.READ) ScsiDirection.IN else ScsiDirection.OUT
        val prepared =
            if (direction == ScsiDirection.IN) buffer.prepareWrite(offset, length)
            else buffer.prepareRead(offset, length)
        if (prepared == null) return BlockResult(BlockStatus.INVALID)
        val maximumBlocks = transport.maximumTransferBytes / size
        if (maximumBlocks == 0) return BlockResult(BlockStatus.INVALID)
        var completed = 0
        while (completed < length) {
            val lba = block + (completed / size).toUInt()
            val count = minOf((length - completed) / size, maximumBlocks)
            val extended =
                lba > UInt.MAX_VALUE.toULong() ||
                    count > 0xffff ||
                    count.toULong() - 1uL > UInt.MAX_VALUE.toULong() - lba
            val command = ByteArray(if (extended) 16 else 10)
            command[0] =
                when {
                    extended && direction == ScsiDirection.IN -> 0x88.toByte()
                    extended -> 0x8a.toByte()
                    direction == ScsiDirection.IN -> 0x28
                    else -> 0x2a
                }
            val fields = BigEndianBuffer(command)
            fields.write(2, if (extended) 8 else 4, lba)
            fields.write(if (extended) 10 else 7, if (extended) 4 else 2, count.toULong())
            val bytes = count * size
            val result =
                transport.execute(lun, command, direction, buffer, offset + completed, bytes)
            if (result.status != ScsiStatus.GOOD || result.actualLength != bytes) {
                return BlockResult(result.blockStatus, completed)
            }
            completed += bytes
        }
        return BlockResult(BlockStatus.SUCCESS, completed)
    }

    override suspend fun flush(): BlockStatus {
        if (readOnly) return BlockStatus.SUCCESS
        val command = ByteArray(10)
        command[0] = 0x35
        val result = transport.execute(lun, command)
        return if (result.status == ScsiStatus.GOOD) BlockStatus.SUCCESS else result.blockStatus
    }

    companion object {
        private val ScsiResult.blockStatus: BlockStatus
            get() =
                when {
                    status == ScsiStatus.NO_DEVICE -> BlockStatus.NO_DEVICE
                    status == ScsiStatus.NO_MEMORY -> BlockStatus.NO_MEMORY
                    sense?.key == 7 -> BlockStatus.READ_ONLY
                    else -> BlockStatus.IO_ERROR
                }

        suspend fun probe(transport: ScsiTransport, lun: ULong): ScsiDisk? {
            val inquiry = ByteArray(36)
            val response = transport.query(lun, byteArrayOf(0x12, 0, 0, 0, 36, 0), inquiry)
            if (
                response.status != ScsiStatus.GOOD ||
                    response.actualLength < inquiry.size ||
                    inquiry[0].toInt() and 0xe0 != 0 ||
                    inquiry[0].toInt() and 31 != 0
            )
                return null
            var ready = false
            repeat(30) {
                if (ready) return@repeat
                val status = transport.execute(lun, ByteArray(6))
                if (status.status == ScsiStatus.GOOD) {
                    ready = true
                    return@repeat
                }
                val sense = status.sense
                if (sense?.key != 6 && !(sense?.key == 2 && sense.code == 4)) return null
                delay(100)
            }
            if (!ready) return null
            var capacity = ByteArray(8)
            var command = ByteArray(10).also { it[0] = 0x25 }
            var result = transport.query(lun, command, capacity)
            if (result.status != ScsiStatus.GOOD || result.actualLength < capacity.size) return null
            var fields = BigEndianBuffer(capacity)
            var last = fields.read(0, 4)
            var size = fields.read(4, 4)
            if (last == UInt.MAX_VALUE.toULong()) {
                capacity = ByteArray(32)
                command =
                    ByteArray(16).also {
                        it[0] = 0x9e.toByte()
                        it[1] = 0x10
                        BigEndianBuffer(it).write(10, 4, capacity.size.toULong())
                    }
                result = transport.query(lun, command, capacity)
                if (result.status != ScsiStatus.GOOD || result.actualLength < 12) return null
                fields = BigEndianBuffer(capacity)
                last = fields.read(0, 8)
                size = fields.read(8, 4)
                if (result.actualLength < 16 || capacity[12].toInt() and 1 != 0) return null
            }
            if (
                size == 0uL ||
                    size > Int.MAX_VALUE.toULong() ||
                    size > transport.maximumTransferBytes.toULong() ||
                    last >= Long.MAX_VALUE.toULong() / size
            )
                return null
            val mode = ByteArray(4)
            result = transport.query(lun, byteArrayOf(0x1a, 8, 0x3f, 0, 4, 0), mode)
            var readOnly =
                result.status == ScsiStatus.GOOD &&
                    result.actualLength >= 4 &&
                    mode[2].toInt() and 0x80 != 0
            if (result.status == ScsiStatus.CHECK_CONDITION && result.sense?.key == 5) {
                val extendedMode = ByteArray(8)
                result =
                    transport.query(
                        lun,
                        byteArrayOf(0x5a, 8, 0x3f, 0, 0, 0, 0, 0, 8, 0),
                        extendedMode,
                    )
                readOnly =
                    result.status == ScsiStatus.GOOD &&
                        result.actualLength >= 8 &&
                        extendedMode[3].toInt() and 0x80 != 0
            }
            val model = inquiry.copyOfRange(8, 32).decodeToString().trim()
            return ScsiDisk(
                transport,
                lun,
                BlockGeometry(size.toInt(), last + 1uL),
                readOnly,
                model,
            )
        }
    }
}
