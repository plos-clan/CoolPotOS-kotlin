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

fun ULong.isPageAligned(): Boolean = isAligned(PAGE_SIZE_BYTES)

fun <T : CPointed> ULong.toVirtualPointer(): CPointer<T>? = Hhdm.toVirtualPointer(this)

fun CPointer<ULongVar>.clear() {
    repeat(PTE_COUNT) { index ->
        this[index] = 0uL
    }
}
