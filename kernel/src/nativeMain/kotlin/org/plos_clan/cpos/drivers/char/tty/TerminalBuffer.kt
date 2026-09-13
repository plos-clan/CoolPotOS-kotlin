package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.mem.PreparedBufferDestination

internal class TerminalBuffer(val capacity: Int = 4096) {
    init { require(capacity > 1) }

    private val data = ByteArray(capacity)
    private val boundaries = ByteArray(capacity)
    private var head = 0
    var size = 0
        private set
    var committed = 0
        private set

    val remaining: Int
        get() = capacity - size
    val pending: Int
        get() = size - committed
    val available: Int
        get() {
            var count = committed
            for (index in 0 until committed) if (boundaries[(head + index) % capacity] == EOF) count--
            return count
        }

    fun offer(value: Byte, boundary: Byte = 0): Boolean {
        if (remaining == 0) return false
        val tail = (head + size) % capacity
        data[tail] = value
        boundaries[tail] = boundary
        size++
        if (boundary != 0.toByte()) committed = size
        return true
    }

    fun write(source: ByteArray, offset: Int, count: Int): Int {
        val copied = minOf(count, remaining)
        val tail = (head + size) % capacity
        val first = minOf(copied, capacity - tail)
        source.copyInto(data, tail, offset, offset + first)
        source.copyInto(data, 0, offset + first, offset + copied)
        boundaries.fill(0, tail, tail + first)
        boundaries.fill(0, 0, copied - first)
        size += copied
        return copied
    }

    fun erase(utf8: Boolean): Int {
        if (pending == 0) return 0
        var erased = 0
        do {
            val value = data[(head + size - 1) % capacity].toInt() and 0xFF
            size--
            erased++
        } while (utf8 && value and 0xC0 == 0x80 && pending != 0)
        return erased
    }

    fun last(): Int? = if (pending == 0) null else data[(head + size - 1) % capacity].toInt() and 0xFF

    fun read(destination: PreparedBufferDestination, offset: Int, count: Int, canonical: Boolean): Int {
        val limit = minOf(count, if (canonical) committed else size)
        var length = limit
        if (canonical) {
            for (index in 0 until limit) {
                val boundary = boundaries[(head + index) % capacity]
                if (boundary == 0.toByte()) continue
                length = index + if (boundary == EOF) 0 else 1
                break
            }
        }
        val first = minOf(length, capacity - head)
        var copied = destination.copyFrom(offset, data, head, first)
        if (copied == first && copied < length) {
            copied += destination.copyFrom(offset + copied, data, 0, length - copied)
        }
        val trailingEof = canonical && copied == length && copied < committed &&
            boundaries[(head + copied) % capacity] == EOF
        val consumed = copied + if (trailingEof) 1 else 0
        head = (head + consumed) % capacity
        size -= consumed
        committed = maxOf(0, committed - consumed)
        return if (length != 0 && copied == 0) -1 else copied
    }

    fun setCanonical(enabled: Boolean) {
        boundaries.fill(0)
        committed = 0
        if (enabled && size != 0) {
            boundaries[(head + size - 1) % capacity] = LINE
            committed = size
        }
    }

    fun clear() {
        head = 0
        size = 0
        committed = 0
    }

    companion object {
        const val LINE: Byte = 1
        const val EOF: Byte = 2
    }
}
