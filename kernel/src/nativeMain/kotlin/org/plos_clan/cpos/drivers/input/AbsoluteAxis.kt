package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal enum class AbsoluteAxis(val code: UShort) {
    X(0u),
    Y(1u),
}

internal data class AbsoluteAxisInfo(
    var value: Int = 0,
    val minimum: Int = 0,
    val maximum: Int = 0,
    val fuzz: Int = 0,
    val flat: Int = 0,
    val resolution: Int = 0,
) {
    fun update(sample: Int): Boolean {
        val distance = sample.toLong() - value
        val tolerance = fuzz.toLong()
        val filtered = when {
            fuzz == 0 -> sample
            distance > -tolerance / 2 && distance < tolerance / 2 -> value
            distance > -tolerance && distance < tolerance -> ((value.toLong() * 3 + sample) / 4).toInt()
            distance > -tolerance * 2 && distance < tolerance * 2 -> ((value.toLong() + sample) / 2).toInt()
            else -> sample
        }
        if (value == filtered) return false
        value = filtered
        return true
    }

    fun encode(): ByteArray {
        val bytes = ByteArray(SIZE_BYTES)
        val data = LittleEndianBuffer(bytes)
        data.writeU32(0, value.toUInt())
        data.writeU32(4, minimum.toUInt())
        data.writeU32(8, maximum.toUInt())
        data.writeU32(12, fuzz.toUInt())
        data.writeU32(16, flat.toUInt())
        data.writeU32(20, resolution.toUInt())
        return bytes
    }

    companion object {
        const val SIZE_BYTES = 24

        fun decode(bytes: ByteArray): AbsoluteAxisInfo {
            val data = LittleEndianBuffer(bytes.copyOf(SIZE_BYTES))
            val resolution = if (bytes.size < SIZE_BYTES) 0 else data.readU32(20).toInt()
            return AbsoluteAxisInfo(
                data.readU32(0).toInt(), data.readU32(4).toInt(), data.readU32(8).toInt(),
                data.readU32(12).toInt(), data.readU32(16).toInt(), resolution,
            )
        }
    }
}
