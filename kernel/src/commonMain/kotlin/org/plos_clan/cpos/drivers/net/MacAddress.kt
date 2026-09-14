@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package org.plos_clan.cpos.drivers.net

import kotlin.jvm.JvmInline

@JvmInline
value class MacAddress private constructor(private val value: ULong) {
    val isUnicast: Boolean
        get() = value != 0uL && this[0] and 1u.toUByte() == 0u.toUByte()

    operator fun get(index: Int): UByte {
        require(index in 0 until SIZE_BYTES)
        return (value shr ((SIZE_BYTES - index - 1) * Byte.SIZE_BITS)).toUByte()
    }

    fun toUByteArray(): UByteArray = UByteArray(SIZE_BYTES, ::get)

    fun copyTo(bytes: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset <= bytes.size - SIZE_BYTES)
        repeat(SIZE_BYTES) { index -> bytes[offset + index] = this[index].toByte() }
    }

    override fun toString(): String = (0 until SIZE_BYTES).joinToString(":") { index ->
        this[index].toString(16).padStart(2, '0')
    }

    companion object {
        const val SIZE_BYTES = 6
        val ZERO = MacAddress(0uL)
        val BROADCAST = MacAddress(0xFFFF_FFFF_FFFFuL)

        fun fromBits(value: ULong): MacAddress? =
            value.takeIf { it shr (SIZE_BYTES * Byte.SIZE_BITS) == 0uL }?.let(::MacAddress)

        fun from(bytes: ByteArray, offset: Int = 0): MacAddress? {
            if (offset < 0 || offset > bytes.size - SIZE_BYTES) return null
            var value = 0uL
            repeat(SIZE_BYTES) { index ->
                value = (value shl Byte.SIZE_BITS) or bytes[offset + index].toUByte().toULong()
            }
            return MacAddress(value)
        }
    }
}
