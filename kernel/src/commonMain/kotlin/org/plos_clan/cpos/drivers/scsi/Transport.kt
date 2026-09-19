package org.plos_clan.cpos.drivers.scsi

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.utils.BigEndianBuffer

enum class ScsiDirection {
    NONE,
    IN,
    OUT,
}

enum class ScsiStatus {
    GOOD,
    CHECK_CONDITION,
    BUSY,
    IO_ERROR,
    NO_DEVICE,
    NO_MEMORY,
}

data class ScsiSense(val key: Int, val code: Int, val qualifier: Int) {
    companion object {
        fun parse(bytes: ByteArray): ScsiSense? {
            if (bytes.isEmpty()) return null
            return when (bytes[0].toInt() and 0x7f) {
                0x70,
                0x71 ->
                    if (bytes.size >= 14 && bytes[7].toUByte().toInt() >= 6) {
                        ScsiSense(
                            bytes[2].toInt() and 15,
                            bytes[12].toUByte().toInt(),
                            bytes[13].toUByte().toInt(),
                        )
                    } else null
                0x72,
                0x73 ->
                    if (bytes.size >= 8) {
                        ScsiSense(
                            bytes[1].toInt() and 15,
                            bytes[2].toUByte().toInt(),
                            bytes[3].toUByte().toInt(),
                        )
                    } else null
                else -> null
            }
        }
    }
}

data class ScsiResult(
    val status: ScsiStatus,
    val actualLength: Int = 0,
    val sense: ScsiSense? = null,
)

abstract class ScsiTransport {
    open val connected: Boolean = true
    abstract val maximumTransferBytes: Int

    abstract suspend fun execute(
        lun: ULong,
        command: ByteArray,
        direction: ScsiDirection = ScsiDirection.NONE,
        buffer: IoBuffer? = null,
        offset: Int = 0,
        length: Int = 0,
    ): ScsiResult

    suspend fun query(lun: ULong, command: ByteArray, destination: ByteArray): ScsiResult {
        val buffer = ByteArrayBuffer(destination)
        var result = ScsiResult(ScsiStatus.IO_ERROR)
        repeat(3) {
            result = execute(lun, command, ScsiDirection.IN, buffer, 0, destination.size)
            if (result.status != ScsiStatus.CHECK_CONDITION || result.sense?.key != 6) return result
        }
        return result
    }

    open suspend fun logicalUnits(): List<ULong> {
        var allocation = 16
        while (true) {
            val bytes = ByteArray(allocation)
            val command = ByteArray(12)
            command[0] = 0xa0.toByte()
            BigEndianBuffer(command).write(6, 4, allocation.toULong())
            val result = query(0uL, command, bytes)
            if (result.status == ScsiStatus.CHECK_CONDITION && result.sense?.key == 5)
                return listOf(0uL)
            if (result.status != ScsiStatus.GOOD || result.actualLength < 8) return emptyList()
            val fields = BigEndianBuffer(bytes)
            val length = fields.read(0, 4)
            if (
                length % 8uL != 0uL ||
                    length > (maximumTransferBytes - 8).coerceAtLeast(0).toULong()
            ) {
                return emptyList()
            }
            val required = length.toInt() + 8
            if (required > allocation) {
                allocation = required
                continue
            }
            if (result.actualLength < required) return emptyList()
            return List(length.toInt() / 8) { fields.read(8 + it * 8, 8) }.distinct()
        }
    }
}
