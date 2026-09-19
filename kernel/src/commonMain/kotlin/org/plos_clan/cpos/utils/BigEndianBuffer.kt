package org.plos_clan.cpos.utils

import kotlin.jvm.JvmInline

@JvmInline
value class BigEndianBuffer(private val bytes: ByteArray) {
    fun read(offset: Int, width: Int): ULong {
        require(width in 1..8 && offset >= 0 && offset <= bytes.size - width)
        var value = 0uL
        repeat(width) { value = (value shl 8) or bytes[offset + it].toUByte().toULong() }
        return value
    }

    fun write(offset: Int, width: Int, value: ULong) {
        require(width in 1..8 && offset >= 0 && offset <= bytes.size - width)
        repeat(width) { bytes[offset + it] = (value shr ((width - it - 1) * 8)).toByte() }
    }
}
