package org.plos_clan.cpos.drivers.usb.xhci.core

internal class RingEntry(
    val trbs: List<Trb>,
    val completion: (Int, Trb) -> Boolean,
) {
    var first = 0
    var completed = false
}

internal class RingQueue(val capacity: Int) {
    private val entries = arrayOfNulls<RingEntry>(capacity)
    private var dequeueIndex = 0

    var enqueueIndex = 0
        private set

    var cycleState = true
        private set

    var used = 0
        private set

    init {
        require(capacity > 1)
    }

    fun enqueue(entry: RingEntry, write: (Int, Boolean, Trb, Boolean) -> Unit): Boolean {
        require(entry.trbs.isNotEmpty())
        if (entry.trbs.size > capacity - 1 - used) return false
        entry.first = enqueueIndex
        entry.trbs.forEachIndexed { index, trb ->
            entries[enqueueIndex] = entry
            write(enqueueIndex, cycleState, trb, index == 0)
            enqueueIndex++
            if (enqueueIndex == capacity) {
                enqueueIndex = 0
                cycleState = !cycleState
            }
        }
        used += entry.trbs.size
        return true
    }

    fun complete(index: Int, event: Trb) {
        val entry = entries.getOrNull(index) ?: return
        if (entry.completed) return
        val offset = (index - entry.first + capacity) % capacity
        if (!entry.completion(offset, event)) return
        entry.completed = true
        while (used != 0) {
            val head = entries[dequeueIndex] ?: break
            if (!head.completed) break
            repeat(head.trbs.size) {
                entries[dequeueIndex] = null
                dequeueIndex = (dequeueIndex + 1) % capacity
            }
            used -= head.trbs.size
        }
    }

    fun discard() {
        entries.fill(null)
        dequeueIndex = enqueueIndex
        used = 0
    }
}
