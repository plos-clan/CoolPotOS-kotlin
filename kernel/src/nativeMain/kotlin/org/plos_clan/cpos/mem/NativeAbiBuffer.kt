@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.plos_clan.cpos.mem

import kotlin.native.getLongAt
import kotlin.native.setLongAt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.readBits
import kotlinx.cinterop.set
import kotlinx.cinterop.writeBits

internal value class NativeAbiBuffer(private val bytes: CPointer<UByteVar>) {
    fun readWord(offset: Int, size: Int): ULong {
        val pointer = (bytes + offset).rawValue
        when (size) {
            Long.SIZE_BYTES -> return readBits(pointer, 0, Long.SIZE_BITS, false).toULong()
            Int.SIZE_BYTES -> return readBits(pointer, 0, Int.SIZE_BITS, false).toULong()
            Short.SIZE_BYTES -> return readBits(pointer, 0, Short.SIZE_BITS, false).toULong()
        }
        var bits = 0uL
        for (i in 0 until size) {
            val value = bytes[offset + i].toULong()
            bits = bits or (value shl (i * Byte.SIZE_BITS))
        }
        return bits
    }

    fun writeWord(offset: Int, size: Int, bits: ULong) {
        val pointer = (bytes + offset).rawValue
        when (size) {
            Long.SIZE_BYTES -> return writeBits(pointer, 0, Long.SIZE_BITS, bits.toLong())
            Int.SIZE_BYTES -> return writeBits(pointer, 0, Int.SIZE_BITS, bits.toLong())
            Short.SIZE_BYTES -> return writeBits(pointer, 0, Short.SIZE_BITS, bits.toLong())
        }
        for (i in 0 until size) {
            bytes[offset + i] = (bits shr (i * Byte.SIZE_BITS)).toUByte()
        }
    }

    fun copyTo(destination: ByteArray, offset: Int, size: Int) {
        var i = 0
        while (i <= size - Long.SIZE_BYTES) {
            val word = readWord(i, Long.SIZE_BYTES)
            destination.setLongAt(offset + i, word.toLong())
            i += Long.SIZE_BYTES
        }
        while (i < size) {
            destination[offset + i] = bytes[i].toByte()
            i++
        }
    }

    fun copyFrom(source: ByteArray, offset: Int, size: Int) {
        var i = 0
        while (i <= size - Long.SIZE_BYTES) {
            val word = source.getLongAt(offset + i)
            writeWord(i, Long.SIZE_BYTES, word.toULong())
            i += Long.SIZE_BYTES
        }
        while (i < size) {
            bytes[i] = source[offset + i].toUByte()
            i++
        }
    }
}
