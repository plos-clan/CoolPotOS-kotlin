package org.plos_clan.cpos.utils

import org.plos_clan.cpos.mem.PreparedBufferDestination

class ByteRingBuffer(capacity: Int = 4096) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "Ring-buffer capacity must be a power of two"
        }
    }

    private val data = ByteArray(capacity)
    private val mask = capacity - 1
    private val lock = IrqSpinLock()
    private val state = Transaction()

    private var readIndex = 0
    private var writeIndex = 0
    private var used = 0

    val available: Int
        get() = transaction { available }

    val remaining: Int
        get() = transaction { remaining }

    fun offer(value: Byte): Boolean = transaction { offer(value) }

    fun write(
        source: ByteArray,
        offset: Int = 0,
        length: Int = source.size - offset,
    ): Int {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            return 0
        }

        return transaction { write(source, offset, length) }
    }

    fun read(
        destination: ByteArray,
        offset: Int = 0,
        length: Int = destination.size - offset,
    ): Int {
        if (offset < 0 || length < 0 || offset > destination.size - length) {
            return 0
        }

        return transaction { read(destination, offset, length) }
    }

    fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        length: Int,
    ): Int = if (offset < 0 || length < 0) 0 else transaction {
        read(destination, offset, length)
    }

    fun clear() = transaction { clear() }

    internal fun <T> transaction(action: Transaction.() -> T): T = lock.withLock {
        state.action()
    }

    internal inner class Transaction internal constructor() {
        val available: Int
            get() = used

        val remaining: Int
            get() = data.size - used

        fun offer(value: Byte): Boolean {
            if (used == data.size) return false
            data[writeIndex] = value
            writeIndex = (writeIndex + 1) and mask
            used++
            return true
        }

        fun write(source: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > source.size - length) return 0
            val accepted = minOf(length, remaining)
            val firstPart = minOf(accepted, data.size - writeIndex)
            source.copyInto(data, writeIndex, offset, offset + firstPart)

            val secondPart = accepted - firstPart
            if (secondPart != 0) {
                source.copyInto(data, 0, offset + firstPart, offset + accepted)
            }
            writeIndex = (writeIndex + accepted) and mask
            used += accepted
            return accepted
        }

        fun read(destination: ByteArray, offset: Int, length: Int): Int =
            if (offset < 0 || length < 0 || offset > destination.size - length) 0 else {
                transfer(length) { sourceOffset, destinationOffset, count ->
                    data.copyInto(destination, offset + destinationOffset, sourceOffset, sourceOffset + count)
                    count
                }
            }

        fun read(destination: PreparedBufferDestination, offset: Int, length: Int): Int =
            if (offset < 0 || length < 0) 0 else {
                transfer(length) { sourceOffset, destinationOffset, count ->
                    destination.copyFrom(offset + destinationOffset, data, sourceOffset, count)
                }
            }

        fun clear() {
            readIndex = 0
            writeIndex = 0
            used = 0
        }

        private inline fun transfer(
            length: Int,
            copy: (sourceOffset: Int, destinationOffset: Int, count: Int) -> Int,
        ): Int {
            val requested = minOf(length, used)
            val firstPart = minOf(requested, data.size - readIndex)
            var transferred = copy(readIndex, 0, firstPart)
            if (transferred == firstPart) {
                val secondPart = requested - firstPart
                if (secondPart != 0) {
                    transferred += copy(0, firstPart, secondPart)
                }
            }
            readIndex = (readIndex + transferred) and mask
            used -= transferred
            return transferred
        }
    }
}
