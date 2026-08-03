@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.Hhdm
import platform.posix.memcpy

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

private fun CPointer<UByteVar>.readLittleEndian(offset: Int, byteCount: Int): ULong =
    (0 until byteCount).fold(0uL) { value, byteIndex ->
        value or (readU8(offset + byteIndex).toULong() shl (byteIndex * Byte.SIZE_BITS))
    }

fun CPointer<UByteVar>.readU16(offset: Int): UShort =
    readLittleEndian(offset, UShort.SIZE_BYTES).toUShort()

fun CPointer<UByteVar>.readU32(offset: Int): UInt =
    readLittleEndian(offset, UInt.SIZE_BYTES).toUInt()

fun CPointer<UByteVar>.readU64(offset: Int): ULong =
    readLittleEndian(offset, ULong.SIZE_BYTES)

fun CPointer<UByteVar>.matchesAscii(offset: Int, text: String): Boolean =
    text.indices.all { index -> readU8(offset + index) == text[index].code.toUByte() }

fun CPointer<UByteVar>.readAscii(offset: Int, length: Int): String =
    CharArray(length) { index -> readU8(offset + index).toInt().toChar() }.concatToString()

fun CPointer<UByteVar>.checksumOk(length: Int): Boolean {
    return length > 0 && (0 until length)
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

fun CPointer<out CPointed>.pointerToByteArray(
    size: ULong
): ByteArray {
    require(size <= Int.MAX_VALUE.toULong()) {
        "ByteArray size exceeds Int.MAX_VALUE: $size"
    }

    val length = size.toInt()
    val result = ByteArray(length)

    if (length == 0) {
        return result
    }

    result.usePinned { pinned ->
        memcpy(
            pinned.addressOf(0),
            this,
            size.convert()
        )
    }

    return result
}

abstract class NativeStruct {
    protected fun putU16LE(
        buffer: ByteArray,
        offset: Int,
        value: Short,
    ) {
        val bits = value.toUShort().toUInt()
        buffer[offset] = (bits and 0xffu).toByte()
        buffer[offset + 1] = ((bits shr 8) and 0xffu).toByte()
    }

    protected fun putU32LE(
        buffer: ByteArray,
        offset: Int,
        value: Int,
    ) {
        val bits = value.toUInt()
        buffer[offset] = (bits and 0xffu).toByte()
        buffer[offset + 1] = ((bits shr 8) and 0xffu).toByte()
        buffer[offset + 2] = ((bits shr 16) and 0xffu).toByte()
        buffer[offset + 3] = ((bits shr 24) and 0xffu).toByte()
    }

    protected fun getU32LE(
        buffer: ByteArray,
        offset: Int,
    ): Int {
        require(offset >= 0 && offset <= buffer.size - UInt.SIZE_BYTES) {
            "32-bit field at offset $offset exceeds a ${buffer.size}-byte structure"
        }
        return (
            buffer[offset].toUByte().toUInt() or
                (buffer[offset + 1].toUByte().toUInt() shl 8) or
                (buffer[offset + 2].toUByte().toUInt() shl 16) or
                (buffer[offset + 3].toUByte().toUInt() shl 24)
        ).toInt()
    }

    abstract fun toNativeBytes(): ByteArray
}
