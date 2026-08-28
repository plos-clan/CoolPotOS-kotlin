@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import org.plos_clan.cpos.mem.Hhdm
import kotlin.experimental.ExperimentalNativeApi

const val PTE_COUNT = 512
const val PAGE_SIZE_BYTES = 4096uL

private const val HEX_RADIX = 16
private const val HEX_PREFIX = "0x"

fun ULong.hasBit(index: Int): Boolean =
    index in 0 until ULong.SIZE_BITS && ((this shr index) and 1uL) != 0uL

fun ULong.hex64(): String = toString(HEX_RADIX).padStart(16, '0')
fun UInt.hex32(): String = toString(HEX_RADIX).padStart(8, '0')
fun ULong.hex(): String = "$HEX_PREFIX${toString(HEX_RADIX)}"
fun UInt.hex(): String = "$HEX_PREFIX${toString(HEX_RADIX)}"

fun ULong.isCanonicalKernelAddress(): Boolean = (this shr 48) == 0xFFFFuL

fun <T : CPointed> ULong.toPointer(): CPointer<T>? = toLong().toCPointer()

fun CPointer<UByteVar>.readU8(offset: Int): UByte = this[offset]

fun CPointer<UByteVar>.readU16(offset: Int): UShort =
    (readU8(offset).toUInt() or
        (readU8(offset + 1).toUInt() shl Byte.SIZE_BITS)).toUShort()

fun CPointer<UByteVar>.readU32(offset: Int): UInt =
    readU16(offset).toUInt() or
        (readU16(offset + UShort.SIZE_BYTES).toUInt() shl UShort.SIZE_BITS)

fun CPointer<UByteVar>.readU64(offset: Int): ULong =
    readU32(offset).toULong() or
        (readU32(offset + UInt.SIZE_BYTES).toULong() shl UInt.SIZE_BITS)

fun CPointer<UByteVar>.matchesAscii(offset: Int, text: String): Boolean =
    text.indices.all { index -> readU8(offset + index) == text[index].code.toUByte() }

fun CPointer<UByteVar>.readAscii(offset: Int, length: Int): String =
    CharArray(length) { index -> readU8(offset + index).toInt().toChar() }.concatToString()

fun CPointer<UByteVar>.checksumOk(length: Int): Boolean {
    return length > 0 && (0 until length)
        .fold(0u) { sum, index -> (sum + readU8(index).toUInt()) and 0xffu } == 0u
}

fun ULong.alignUp(alignment: ULong): ULong? {
    require(alignment != 0uL) { "Alignment must be positive" }
    val adjustment = if (alignment and (alignment - 1uL) == 0uL) {
        val mask = alignment - 1uL
        (alignment - (this and mask)) and mask
    } else {
        val remainder = this % alignment
        if (remainder == 0uL) 0uL else alignment - remainder
    }
    return if (this <= ULong.MAX_VALUE - adjustment) this + adjustment else null
}

fun ULong.alignDown(alignment: ULong): ULong {
    require(alignment != 0uL) { "Alignment must be positive" }
    return if (alignment and (alignment - 1uL) == 0uL) {
        this and (alignment - 1uL).inv()
    } else {
        this - this % alignment
    }
}

fun ULong.isAligned(alignment: ULong): Boolean =
    when {
        alignment == 0uL -> false
        alignment and (alignment - 1uL) == 0uL -> this and (alignment - 1uL) == 0uL
        else -> this % alignment == 0uL
    }

fun UInt.hasBit(bit: Int): Boolean {
    return (this and (1u shl bit)) != 0u
}

fun ULong.isPageAligned(): Boolean = isAligned(PAGE_SIZE_BYTES)

fun <T : CPointed> ULong.toVirtualPointer(): CPointer<T>? = Hhdm.toVirtualPointer(this)

fun CPointer<ULongVar>.clear() {
    repeat(PTE_COUNT) { index ->
        this[index] = 0uL
    }
}

fun ByteArray.readULongLE(offset: Int): ULong {
    var result = 0uL

    for (i in 0 until 8) {
        result = result or (
                (this[offset + i].toULong() and 0xffuL) shl (i * 8)
                )
    }

    return result
}

fun Long.toByteArray(): ByteArray {
    return ByteArray(8).also {
        it.setLongAt(0, this)
    }
}

value class LittleEndianBuffer(private val bytes: ByteArray) {
    fun readU8(offset: Int): UByte {
        requireRange(offset, UByte.SIZE_BYTES)
        return bytes[offset].toUByte()
    }

    fun readU16(offset: Int): UShort {
        requireRange(offset, UShort.SIZE_BYTES)
        return (bytes[offset].toUByte().toUInt() or
            (bytes[offset + 1].toUByte().toUInt() shl Byte.SIZE_BITS)).toUShort()
    }

    fun readU32(offset: Int): UInt {
        requireRange(offset, UInt.SIZE_BYTES)
        return bytes[offset].toUByte().toUInt() or
            (bytes[offset + 1].toUByte().toUInt() shl 8) or
            (bytes[offset + 2].toUByte().toUInt() shl 16) or
            (bytes[offset + 3].toUByte().toUInt() shl 24)
    }

    fun readU64(offset: Int): ULong {
        requireRange(offset, ULong.SIZE_BYTES)
        return bytes[offset].toUByte().toULong() or
            (bytes[offset + 1].toUByte().toULong() shl 8) or
            (bytes[offset + 2].toUByte().toULong() shl 16) or
            (bytes[offset + 3].toUByte().toULong() shl 24) or
            (bytes[offset + 4].toUByte().toULong() shl 32) or
            (bytes[offset + 5].toUByte().toULong() shl 40) or
            (bytes[offset + 6].toUByte().toULong() shl 48) or
            (bytes[offset + 7].toUByte().toULong() shl 56)
    }

    fun readUnsigned(offset: Int, byteCount: Int): ULong {
        require(byteCount in 0..ULong.SIZE_BYTES) {
            "Little-endian integer width must be between 0 and ${ULong.SIZE_BYTES} bytes"
        }
        return when (byteCount) {
            0 -> {
                requireRange(offset, 0)
                0uL
            }
            UByte.SIZE_BYTES -> readU8(offset).toULong()
            UShort.SIZE_BYTES -> readU16(offset).toULong()
            UInt.SIZE_BYTES -> readU32(offset).toULong()
            ULong.SIZE_BYTES -> readU64(offset)
            else -> {
                requireRange(offset, byteCount)
                var value = 0uL
                repeat(byteCount) { index ->
                    value = value or
                        (bytes[offset + index].toUByte().toULong() shl (index * Byte.SIZE_BITS))
                }
                value
            }
        }
    }

    fun writeU8(offset: Int, value: UByte) {
        requireRange(offset, UByte.SIZE_BYTES)
        bytes[offset] = value.toByte()
    }

    fun writeU16(offset: Int, value: UShort) {
        requireRange(offset, UShort.SIZE_BYTES)
        val bits = value.toUInt()
        bytes[offset] = bits.toByte()
        bytes[offset + 1] = (bits shr 8).toByte()
    }

    fun writeU32(offset: Int, value: UInt) {
        requireRange(offset, UInt.SIZE_BYTES)
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    fun writeU64(offset: Int, value: ULong) {
        requireRange(offset, ULong.SIZE_BYTES)
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
        bytes[offset + 4] = (value shr 32).toByte()
        bytes[offset + 5] = (value shr 40).toByte()
        bytes[offset + 6] = (value shr 48).toByte()
        bytes[offset + 7] = (value shr 56).toByte()
    }

    private fun requireRange(offset: Int, byteCount: Int) {
        require(offset >= 0 && byteCount >= 0 && offset <= bytes.size - byteCount) {
            "$byteCount-byte field at offset $offset exceeds a ${bytes.size}-byte buffer"
        }
    }
}

internal fun String.decimalInt(): Int? {
    if (isEmpty() || length > 1 && this[0] == '0') return null
    var result = 0
    for (character in this) {
        val digit = character.code - '0'.code
        if (digit !in 0..9 || result > (Int.MAX_VALUE - digit) / 10) return null
        result = result * 10 + digit
    }
    return result
}

interface NativeStruct {
    fun toNativeBytes(): ByteArray
    fun updateFromNativeBytes(buffer: ByteArray): Boolean = false
}
