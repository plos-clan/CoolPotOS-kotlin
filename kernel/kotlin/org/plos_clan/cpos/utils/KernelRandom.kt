package org.plos_clan.cpos.utils

import org.plos_clan.cpos.drivers.TscClock

/** Small boot-time entropy source used until a hardware entropy driver exists. */
object KernelRandom {
    private const val GOLDEN_RATIO = 0x9e37_79b9_7f4a_7c15uL
    private const val MIX_MULTIPLIER = 0xbf58_476d_1ce4_e5b9uL
    private const val MIX_MULTIPLIER_2 = 0x94d0_49bb_1331_11ebuL

    private val lock = IrqSpinLock()
    private var state = 0x6a09_e667_f3bc_c909uL

    fun bytes(size: Int, salt: ULong = 0uL): ByteArray = ByteArray(size).also {
        fill(it, salt = salt)
    }

    fun fill(
        destination: ByteArray,
        offset: Int = 0,
        size: Int = destination.size - offset,
        salt: ULong = 0uL,
    ) {
        require(offset >= 0 && size >= 0 && offset <= destination.size - size)
        lock.withLock {
            state = mix(state xor TscClock.nanoTime() xor salt)
            var cursor = offset
            val end = offset + size
            while (cursor < end) {
                val word = nextWord()
                repeat(minOf(ULong.SIZE_BYTES, end - cursor)) { index ->
                    destination[cursor + index] = (word shr (index * Byte.SIZE_BITS)).toByte()
                }
                cursor += ULong.SIZE_BYTES
            }
        }
    }

    private fun nextWord(): ULong {
        state += GOLDEN_RATIO
        return mix(state)
    }

    private fun mix(value: ULong): ULong {
        var mixed = value
        mixed = (mixed xor (mixed shr 30)) * MIX_MULTIPLIER
        mixed = (mixed xor (mixed shr 27)) * MIX_MULTIPLIER_2
        return mixed xor (mixed shr 31)
    }
}
