@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class OomScoreAdjustment {
    private val state = AtomicInt(pack(DEFAULT, DEFAULT))

    val value: Int
        get() = unpackValue(state.load())

    fun update(replacement: Int, maySetMinimum: Boolean): Boolean {
        require(replacement in MINIMUM..MAXIMUM)
        var observed = state.load()
        while (true) {
            val minimum = unpackMinimum(observed)
            if (!maySetMinimum && replacement < minimum) return false
            val updated = pack(
                replacement,
                if (maySetMinimum) replacement else minimum,
            )
            if (updated == observed || state.compareAndSet(observed, updated)) return true
            observed = state.load()
        }
    }

    fun inherit(parent: OomScoreAdjustment) {
        state.store(parent.state.load())
    }

    companion object {
        const val MINIMUM = -1000
        const val MAXIMUM = 1000
        private const val DEFAULT = 0
        private const val VALUE_MASK = 0xffff

        fun parse(input: ByteArray): Int? {
            var end = input.indexOf(0).takeIf { it >= 0 } ?: input.size
            var start = 0
            while (start < end && input[start].isAsciiWhitespace()) start++
            while (start < end && input[end - 1].isAsciiWhitespace()) end--
            if (start == end) return null

            val negative = input[start].toInt() == '-'.code
            if (negative || input[start].toInt() == '+'.code) {
                start++
                if (start == end) return null
            }

            var radix = 10
            if (input[start].toInt() == '0'.code) {
                radix = 8
                if (start + 1 < end &&
                    (input[start + 1].toInt() == 'x'.code ||
                        input[start + 1].toInt() == 'X'.code)
                ) {
                    radix = 16
                    start += 2
                    if (start == end) return null
                }
            }

            var value = 0
            while (start < end) {
                val character = input[start++].toInt()
                val digit = when (character) {
                    in '0'.code..'9'.code -> character - '0'.code
                    in 'a'.code..'f'.code -> character - 'a'.code + 10
                    in 'A'.code..'F'.code -> character - 'A'.code + 10
                    else -> return null
                }
                if (digit >= radix || value > (MAXIMUM - digit) / radix) return null
                value = value * radix + digit
            }
            return if (negative) -value else value
        }

        private fun pack(value: Int, minimum: Int): Int =
            minimum.shl(Short.SIZE_BITS) or (value and VALUE_MASK)

        private fun unpackValue(state: Int): Int = state.toShort().toInt()

        private fun unpackMinimum(state: Int): Int = state shr Short.SIZE_BITS

        private fun Byte.isAsciiWhitespace(): Boolean =
            toInt() == ' '.code || toInt() in '\t'.code..'\r'.code
    }
}
