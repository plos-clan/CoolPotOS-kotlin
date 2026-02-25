@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.Hhdm

const val PAGE_SIZE = 4096L
const val PTE_COUNT = 512
const val PAGE_SIZE_BYTES: ULong = 4096uL

fun ULong.hasBit(index: Int): Boolean {
    if (index < 0 || index >= ULong.SIZE_BITS) {
        return false
    }
    return ((this shr index) and 1uL) != 0uL
}

fun ULong.hex64(): String = toString(16).padStart(16, '0')

fun <T : CPointed> ULong.toPointer(): CPointer<T>? = toLong().toCPointer()

fun CPointer<UByteVar>.readU8(offset: Int): UByte = this[offset]

fun CPointer<UByteVar>.readU32(offset: Int): UInt {
    val b0 = readU8(offset).toULong()
    val b1 = readU8(offset + 1).toULong()
    val b2 = readU8(offset + 2).toULong()
    val b3 = readU8(offset + 3).toULong()
    return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
}

fun CPointer<UByteVar>.readU64(offset: Int): ULong {
    val b0 = readU8(offset).toULong()
    val b1 = readU8(offset + 1).toULong()
    val b2 = readU8(offset + 2).toULong()
    val b3 = readU8(offset + 3).toULong()
    val b4 = readU8(offset + 4).toULong()
    val b5 = readU8(offset + 5).toULong()
    val b6 = readU8(offset + 6).toULong()
    val b7 = readU8(offset + 7).toULong()
    return b0 or
            (b1 shl 8) or
            (b2 shl 16) or
            (b3 shl 24) or
            (b4 shl 32) or
            (b5 shl 40) or
            (b6 shl 48) or
            (b7 shl 56)
}

fun CPointer<UByteVar>.matchesAscii(offset: Int, text: String): Boolean =
    text.indices.all { index -> readU8(offset + index) == text[index].code.toUByte() }

fun CPointer<UByteVar>.readAscii(offset: Int, length: Int): String =
    CharArray(length) { index -> readU8(offset + index).toInt().toChar() }.concatToString()

fun CPointer<UByteVar>.checksumOk(length: Int): Boolean {
    if (length <= 0) {
        return false
    }
    val sum = (0 until length).fold(0u) { acc, index ->
        (acc + readU8(index).toUInt()) and 0xffu
    }
    return sum == 0u
}

fun UInt.hex32(): String = toString(16).padStart(8, '0')

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

fun ULong.toVirtualAddress(): ULong = Hhdm.toVirtual(this)

fun ULong.toPhysicalAddress(): ULong = Hhdm.toPhysical(this)

fun <T : CPointed> ULong.toVirtualPointer(): CPointer<T>? = Hhdm.toVirtualPointer(this)

fun <T : CPointed> ULong.toPhysicalPointer(): CPointer<T>? = Hhdm.toPhysicalPointer(this)

fun <T : CPointed> CPointer<T>.toVirtualPointer(): CPointer<T>? = Hhdm.toVirtualPointer(this)

fun <T : CPointed> CPointer<T>.toPhysicalPointer(): CPointer<T>? = Hhdm.toPhysicalPointer(this)

fun <T : CPointed> CPointer<T>.toAddress(): ULong = rawValue.toLong().toULong()

fun ULong.toHex64(): String = toString(16).padStart(16, '0')

fun CPointer<ULongVar>.clear() {
    for (index in 0 until PTE_COUNT) {
        this[index] = 0uL
    }
}

