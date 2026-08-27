@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.plos_clan.cpos.drivers.TscClock
import kotlin.experimental.ExperimentalNativeApi

object KernelRandom {
    private const val GOLDEN_RATIO = 0x9e37_79b9_7f4a_7c15uL
    private const val MIX_MULTIPLIER = 0xbf58_476d_1ce4_e5b9uL
    private const val MIX_MULTIPLIER_2 = 0x94d0_49bb_1331_11ebuL
    private const val RDSEED_RETRIES = 64
    private const val RDRAND_RETRIES = 10
    private const val RESEED_INTERVAL_BYTES = 1_048_576uL
    private const val RESEED_INTERVAL_NANOS = 60_000_000_000uL

    private val lock = IrqSpinLock()
    private var state = 0x6a09_e667_f3bc_c909uL
    private var rdseedSupported = false
    private var rdrandSupported = false
    private var generatedSinceReseed = 0uL
    private var lastReseedNanos = 0uL
    private var reseedPending = false

    private fun tryRdseedWord(): ULong? = memScoped {
        val output = alloc<ULongVar>()

        repeat(RDSEED_RETRIES) {
            if (bridge.rdseed64_step(output.ptr)) {
                return@memScoped output.value
            }
            bridge.asm_pause()
        }

        null
    }

    private fun tryRdrandWord(): ULong? = memScoped {
        val output = alloc<ULongVar>()

        repeat(RDRAND_RETRIES) {
            if (bridge.rdrand64_step(output.ptr)) {
                return@memScoped output.value
            }
            bridge.asm_pause()
        }

        null
    }

    private fun getSeed(): ULong =
        (if (rdseedSupported) tryRdseedWord() else null)
            ?: (if (rdrandSupported) tryRdrandWord() else null)
            ?: TscClock.nanoTime()

    fun initialize() {
        rdseedSupported = CpuID.has(CpuFeature.RDSEED)
        rdrandSupported = CpuID.has(CpuFeature.RDRAND)
        val seed = getSeed()
        val now = TscClock.nanoTime()
        lock.withLock {
            reseedLocked(seed, now)
        }
    }

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
        if (size == 0) return
        val now = TscClock.nanoTime()
        val shouldReseed = lock.withLock {
            state = mix(state xor now xor salt)
            var cursor = offset
            val end = offset + size
            while (cursor < end) {
                val word = nextWord()
                repeat(minOf(ULong.SIZE_BYTES, end - cursor)) { index ->
                    destination[cursor + index] = (word shr (index * Byte.SIZE_BITS)).toByte()
                }
                cursor += ULong.SIZE_BYTES
            }

            generatedSinceReseed += size.toULong()
            val due = generatedSinceReseed >= RESEED_INTERVAL_BYTES ||
                now - lastReseedNanos >= RESEED_INTERVAL_NANOS
            if (due && !reseedPending) {
                reseedPending = true
                true
            } else {
                false
            }
        }

        if (shouldReseed) {
            val seed = getSeed()
            val reseedTime = TscClock.nanoTime()
            lock.withLock {
                reseedLocked(seed, reseedTime)
            }
        }
    }

    private fun reseedLocked(seed: ULong, now: ULong) {
        state = mix(state xor seed xor now)
        generatedSinceReseed = 0uL
        lastReseedNanos = now
        reseedPending = false
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
