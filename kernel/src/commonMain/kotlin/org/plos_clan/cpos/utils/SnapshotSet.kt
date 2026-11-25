@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.utils

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.update

internal class SnapshotSet<T> {
    private val entries = AtomicReference(emptyList<T>())

    val snapshot: List<T> get() = entries.load()

    fun add(value: T): Boolean {
        entries.update { current ->
            if (value in current) return false
            buildList(current.size + 1) {
                addAll(current)
                add(value)
            }
        }
        return true
    }

    fun remove(value: T): Unit = entries.update { current ->
        val index = current.indexOf(value)
        if (index < 0) return
        buildList(current.size - 1) {
            for (position in current.indices) if (position != index) add(current[position])
        }
    }
}
