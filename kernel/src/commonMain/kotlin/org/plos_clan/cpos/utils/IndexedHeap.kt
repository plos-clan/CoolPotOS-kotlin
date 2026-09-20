package org.plos_clan.cpos.utils

internal class IndexedHeap<T>(private val comparator: Comparator<T>) {
    class Entry<T> internal constructor(val value: T, internal var index: Int)

    private val entries = ArrayList<Entry<T>>()
    val size: Int get() = entries.size
    fun peek(): T? = entries.firstOrNull()?.value

    fun add(value: T): Entry<T> {
        val entry = Entry(value, entries.size)
        entries.add(entry)
        siftUp(entry.index)
        return entry
    }

    fun remove(entry: Entry<T>): Boolean {
        if (entries.getOrNull(entry.index) !== entry) return false
        val index = entry.index
        val last = entries.removeAt(entries.lastIndex)
        entry.index = -1
        if (index == entries.size) return true
        entries[index] = last
        last.index = index
        if (index > 0 && before(index, (index - 1) / 2)) siftUp(index) else siftDown(index)
        return true
    }

    fun removeFirst(): T? {
        val entry = entries.firstOrNull() ?: return null
        remove(entry)
        return entry.value
    }

    private fun siftUp(start: Int) {
        var index = start
        while (index > 0) {
            val parent = (index - 1) / 2
            if (!before(index, parent)) return
            swap(index, parent)
            index = parent
        }
    }

    private fun siftDown(start: Int) {
        var index = start
        while (index < entries.size / 2) {
            val left = index * 2 + 1
            val right = left + 1
            val child = if (right < entries.size && before(right, left)) right else left
            if (!before(child, index)) return
            swap(index, child)
            index = child
        }
    }

    private fun before(first: Int, second: Int): Boolean =
        comparator.compare(entries[first].value, entries[second].value) < 0

    private fun swap(first: Int, second: Int) {
        val entry = entries[first]
        entries[first] = entries[second]
        entries[second] = entry
        entries[first].index = first
        entry.index = second
    }
}
