package org.plos_clan.cpos.utils

import kotlin.jvm.JvmInline

@JvmInline
value class BigEndianBuffer(private val bytes: ByteArray) {
    fun readU8(offset: Int): UByte = bytes[offset].toUByte()
    fun readU16(offset: Int): UShort {
        require(offset >= 0 && offset <= bytes.size - UShort.SIZE_BYTES)
        return ((readU8(offset).toInt() shl 8) or readU8(offset + 1).toInt()).toUShort()
    }

    fun readU32(offset: Int): UInt {
        require(offset >= 0 && offset <= bytes.size - UInt.SIZE_BYTES)
        return (readU8(offset).toUInt() shl 24) or (readU8(offset + 1).toUInt() shl 16) or
            (readU8(offset + 2).toUInt() shl 8) or readU8(offset + 3).toUInt()
    }

    fun writeU8(offset: Int, value: UByte) {
        bytes[offset] = value.toByte()
    }

    fun writeU16(offset: Int, value: UShort) {
        require(offset >= 0 && offset <= bytes.size - UShort.SIZE_BYTES)
        bytes[offset] = (value.toUInt() shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    fun writeU32(offset: Int, value: UInt) {
        require(offset >= 0 && offset <= bytes.size - UInt.SIZE_BYTES)
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

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
