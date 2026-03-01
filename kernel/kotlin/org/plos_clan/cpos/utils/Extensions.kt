@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.Hhdm

const val PTE_COUNT = 512
const val PAGE_SIZE_BYTES = 4096uL

private const val HEX_RADIX = 16
private const val HEX_PREFIX = "0x"

fun ULong.hasBit(index: Int): Boolean =
    index in 0 until ULong.SIZE_BITS && ((this shr index) and 1uL) != 0uL

fun ULong.hex64(): String = toString(HEX_RADIX).padStart(16, '0')
fun UInt.hex32(): String = toString(HEX_RADIX).padStart(8, '0')
fun ULong.hex(): String = "$HEX_PREFIX${hex64()}"
fun UInt.hex(): String = "$HEX_PREFIX${hex32()}"

fun ULong.isCanonicalKernelAddress(): Boolean = (this shr 48) == 0xFFFFuL

fun <T : CPointed> ULong.toPointer(): CPointer<T>? = toLong().toCPointer()

fun CPointer<UByteVar>.readU8(offset: Int): UByte = this[offset]

fun CPointer<UByteVar>.readU16(offset: Int): UShort {
    val low = readU8(offset).toUInt()
    val high = readU8(offset + 1).toUInt()
    return (low or (high shl 8)).toUShort()
}

fun CPointer<UByteVar>.readU32(offset: Int): UInt =
    (0 until UInt.SIZE_BYTES).fold(0uL) { value, byteIndex ->
        value or (readU8(offset + byteIndex).toULong() shl (byteIndex * Byte.SIZE_BITS))
    }.toUInt()

fun CPointer<UByteVar>.readU64(offset: Int): ULong =
    (0 until ULong.SIZE_BYTES).fold(0uL) { value, byteIndex ->
        value or (readU8(offset + byteIndex).toULong() shl (byteIndex * Byte.SIZE_BITS))
    }

fun CPointer<UByteVar>.matchesAscii(offset: Int, text: String): Boolean =
    text.indices.all { index -> readU8(offset + index) == text[index].code.toUByte() }

fun CPointer<UByteVar>.readAscii(offset: Int, length: Int): String =
    CharArray(length) { index -> readU8(offset + index).toInt().toChar() }.concatToString()

fun CPointer<UByteVar>.checksumOk(length: Int): Boolean {
    if (length <= 0) {
        return false
    }
    return (0 until length)
        .fold(0u) { sum, index -> (sum + readU8(index).toUInt()) and 0xffu } == 0u
}

fun ULong.alignUp(alignment: ULong): ULong {
    if (alignment == 0uL) {
        return this
    }
    val mask = alignment - 1uL
    return (this + mask) and mask.inv()
}

fun ULong.alignDown(alignment: ULong): ULong {
    if (alignment == 0uL) {
        return this
    }
    val mask = alignment - 1uL
    return this and mask.inv()
}

fun ULong.isPageAligned(): Boolean = (this and (PAGE_SIZE_BYTES - 1uL)) == 0uL

fun <T : CPointed> ULong.toVirtualPointer(): CPointer<T>? = Hhdm.toVirtualPointer(this)

fun CPointer<ULongVar>.clear() {
    repeat(PTE_COUNT) { index ->
        this[index] = 0uL
    }
}
