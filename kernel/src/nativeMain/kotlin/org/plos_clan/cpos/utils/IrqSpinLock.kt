@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class, InternalForKotlinNative::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.internal.GCUnsafeCall
import kotlin.native.internal.InternalForKotlinNative

@PublishedApi
@GCUnsafeCall("irq_save")
internal external fun saveInterrupts(): ULong

@PublishedApi
@GCUnsafeCall("irq_restore")
internal external fun restoreInterrupts(flags: ULong)

class IrqSpinLock : CriticalSection() {
    private companion object {
        val hashCollision = IrqSpinLock()
    }

    @PublishedApi
    internal val held = AtomicBoolean(false)

    override fun acquire(): ULong {
        val flags = saveInterrupts()
        while (!held.compareAndSet(expectedValue = false, newValue = true)) {
            if (!bridge.fast_handoff_yield()) bridge.asm_pause()
        }
        return flags
    }

    override fun release(state: ULong) {
        held.store(false)
        restoreInterrupts(state)
    }

    internal fun <T> withLock(peer: IrqSpinLock, block: () -> T): T {
        if (this === peer) return withLock(block)
        fun acquireBoth(first: IrqSpinLock, second: IrqSpinLock): T =
            first.withLock { second.withLock(block) }

        val order = hashCode().compareTo(peer.hashCode())
        return when {
            order < 0 -> acquireBoth(this, peer)
            order > 0 -> acquireBoth(peer, this)
            else -> hashCollision.withLock { acquireBoth(this, peer) }
        }
    }

    inline fun tryWithLock(block: () -> Unit): Boolean {
        val flags = saveInterrupts()
        if (!held.compareAndSet(expectedValue = false, newValue = true)) {
            restoreInterrupts(flags)
            return false
        }
        try {
            block()
        } finally {
            held.store(false)
            restoreInterrupts(flags)
        }
        return true
    }
}
