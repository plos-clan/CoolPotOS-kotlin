package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal abstract class SocketControlMessage(
    val level: Int,
    val type: Int,
    val payloadSize: Int,
) {
    val length: Int
        get() = HEADER_SIZE + payloadSize

    val space: Int
        get() = space(payloadSize)

    fun writeTo(bytes: ByteArray, offset: Int): Int {
        val available = bytes.size - offset
        if (available < HEADER_SIZE) return 0
        val copied = minOf(length, available)
        val output = LittleEndianBuffer(bytes)
        output.writeU64(offset, copied.toULong())
        output.writeU32(offset + ULong.SIZE_BYTES, level.toUInt())
        output.writeU32(offset + ULong.SIZE_BYTES + Int.SIZE_BYTES, type.toUInt())
        writePayload(output, offset + HEADER_SIZE, copied - HEADER_SIZE)
        return minOf(space, available)
    }

    protected abstract fun writePayload(output: LittleEndianBuffer, offset: Int, length: Int)

    class Integers(level: Int, type: Int, private val values: IntArray) : SocketControlMessage(
        level,
        type,
        values.size * Int.SIZE_BYTES,
    ) {
        override fun writePayload(output: LittleEndianBuffer, offset: Int, length: Int) {
            val complete = length / Int.SIZE_BYTES
            repeat(complete) { index ->
                output.writeU32(offset + index * Int.SIZE_BYTES, values[index].toUInt())
            }
            for (index in complete * Int.SIZE_BYTES until length) {
                output.writeU8(
                    offset + index,
                    (values[complete] ushr ((index % Int.SIZE_BYTES) * Byte.SIZE_BITS)).toUByte(),
                )
            }
        }
    }

    companion object {
        const val HEADER_SIZE = ULong.SIZE_BYTES + Int.SIZE_BYTES * 2

        fun space(payloadSize: Int): Int =
            (HEADER_SIZE + payloadSize + ULong.SIZE_BYTES - 1) and (ULong.SIZE_BYTES - 1).inv()
    }
}
