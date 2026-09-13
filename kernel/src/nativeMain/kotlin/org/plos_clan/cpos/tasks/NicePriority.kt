@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class NicePriority(initial: Int = 0) {
    private val state = AtomicInt(initial)

    val value: Int
        get() = state.load()

    val encoded: Int
        get() = 20 - value

    fun set(requested: Int, limit: ULong, privileged: Boolean): Boolean {
        val replacement = requested.coerceIn(-20, 19)
        val mayRaise = privileged || (20 - replacement).toULong() <= limit
        var observed = state.load()
        while (true) {
            if (replacement < observed && !mayRaise) return false
            if (replacement == observed || state.compareAndSet(observed, replacement)) return true
            observed = state.load()
        }
    }
}
