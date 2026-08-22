@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get

internal interface AmlByteSource {
    val size: Int
    fun readByte(offset: Int): UByte
}

internal class AmlPointerSource(
    private val pointer: CPointer<UByteVar>,
    override val size: Int,
) : AmlByteSource {
    override fun readByte(offset: Int): UByte = pointer[offset]
}

internal class AmlArraySource(
    private val bytes: ByteArray,
) : AmlByteSource {
    override val size: Int
        get() = bytes.size

    override fun readByte(offset: Int): UByte = bytes[offset].toUByte()
}

internal data class AmlPackageLength(
    val encodedSize: Int,
    val totalLength: Int,
    val contentStart: Int,
    val end: Int,
)

internal class AmlByteReader(
    val source: AmlByteSource,
    val start: Int = 0,
    val end: Int = source.size,
    position: Int = start,
) {
    var position: Int = position
        private set

    val remaining: Int
        get() = end - position

    val exhausted: Boolean
        get() = position >= end

    init {
        require(start in 0..end && end <= source.size)
        require(position in start..end)
    }

    fun copy(): AmlByteReader = AmlByteReader(source, start, end, position)

    fun peek(relativeOffset: Int = 0): UInt? {
        val offset = position + relativeOffset
        return if (offset in start until end) source.readByte(offset).toUInt() else null
    }

    fun readU8(): UInt? = peek()?.also { position++ }

    fun readU16(): UInt? = readLittleEndian(2)?.toUInt()

    fun readU32(): UInt? = readLittleEndian(4)?.toUInt()

    fun readU64(): ULong? = readLittleEndian(8)

    fun readBytes(count: Int): ByteArray? {
        if (!canRead(count)) {
            return null
        }
        return ByteArray(count) { source.readByte(position++).toByte() }
    }

    fun readAscii(count: Int): String? {
        val bytes = readBytes(count) ?: return null
        return CharArray(bytes.size) { bytes[it].toUByte().toInt().toChar() }.concatToString()
    }

    fun readNullTerminatedAscii(maxLength: Int = remaining): String? {
        if (maxLength < 0) {
            return null
        }
        val chars = mutableListOf<Char>()
        repeat(minOf(maxLength, remaining)) {
            val value = readU8() ?: return null
            if (value == 0u) {
                return chars.toCharArray().concatToString()
            }
            chars += value.toInt().toChar()
        }
        return null
    }

    fun skip(count: Int): Boolean {
        if (!canRead(count)) {
            return false
        }
        position += count
        return true
    }

    fun seek(absoluteOffset: Int): Boolean {
        if (absoluteOffset !in start..end) {
            return false
        }
        position = absoluteOffset
        return true
    }

    fun slice(sliceStart: Int, sliceEnd: Int): AmlByteReader? =
        if (sliceStart in start..sliceEnd && sliceEnd <= end) {
            AmlByteReader(source, sliceStart, sliceEnd)
        } else {
            null
        }

    fun readPackageLength(): AmlPackageLength? {
        val probe = copy()
        val lengthOffset = probe.position
        val encoded = probe.readEncodedLength() ?: return null
        val totalLength = encoded.first
        val encodedSize = encoded.second
        if (totalLength < encodedSize.toULong()) {
            return null
        }
        val packageEnd = lengthOffset.toULong() + totalLength
        if (packageEnd > end.toULong() || packageEnd > Int.MAX_VALUE.toULong()) {
            return null
        }
        position = probe.position
        return AmlPackageLength(
            encodedSize = encodedSize,
            totalLength = totalLength.toInt(),
            contentStart = position,
            end = packageEnd.toInt(),
        )
    }

    fun readFieldLength(): ULong? = readEncodedLength()?.first

    private fun readEncodedLength(): Pair<ULong, Int>? {
        val probe = copy()
        val encoded = probe.decodeEncodedLength() ?: return null
        position = probe.position
        return encoded
    }

    private fun decodeEncodedLength(): Pair<ULong, Int>? {
        val lead = readU8() ?: return null
        val followingByteCount = (lead shr 6).toInt()
        var value = (if (followingByteCount == 0) lead and 0x3Fu else lead and 0x0Fu).toULong()

        repeat(followingByteCount) { index ->
            val next = readU8() ?: return null
            value = value or (next.toULong() shl (4 + index * 8))
        }
        return value to followingByteCount + 1
    }

    private fun canRead(count: Int): Boolean =
        count in 0..remaining

    private fun readLittleEndian(byteCount: Int): ULong? {
        if (!canRead(byteCount)) {
            return null
        }
        var value = 0uL
        repeat(byteCount) { index ->
            value = value or (source.readByte(position++).toULong() shl (index * 8))
        }
        return value
    }
}
