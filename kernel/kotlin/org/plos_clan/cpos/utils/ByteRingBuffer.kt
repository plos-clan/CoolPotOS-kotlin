package org.plos_clan.cpos.utils

class ByteRingBuffer(capacity: Int = 4096) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "Ring-buffer capacity must be a power of two"
        }
    }

    private val data = ByteArray(capacity)
    private val mask = capacity - 1
    private val lock = IrqSpinLock()

    private var readIndex = 0
    private var writeIndex = 0
    private var used = 0

    val available: Int
        get() = lock.withLock { used }

    val remaining: Int
        get() = lock.withLock { data.size - used }

    fun offer(value: Byte): Boolean = lock.withLock {
        if (used == data.size) {
            return@withLock false
        }

        data[writeIndex] = value
        writeIndex = (writeIndex + 1) and mask
        used++
        true
    }

    fun write(
        source: ByteArray,
        offset: Int = 0,
        length: Int = source.size - offset,
    ): Int {
        if (offset < 0 || length < 0 || offset > source.size - length) {
            return 0
        }

        return lock.withLock {
            val accepted = minOf(length, data.size - used)
            val firstPart = minOf(accepted, data.size - writeIndex)

            source.copyInto(
                destination = data,
                destinationOffset = writeIndex,
                startIndex = offset,
                endIndex = offset + firstPart,
            )

            val secondPart = accepted - firstPart
            if (secondPart != 0) {
                source.copyInto(
                    destination = data,
                    destinationOffset = 0,
                    startIndex = offset + firstPart,
                    endIndex = offset + accepted,
                )
            }

            writeIndex = (writeIndex + accepted) and mask
            used += accepted
            accepted
        }
    }

    fun read(
        destination: ByteArray,
        offset: Int = 0,
        length: Int = destination.size - offset,
    ): Int {
        if (offset < 0 || length < 0 || offset > destination.size - length) {
            return 0
        }

        return lock.withLock {
            val transferred = minOf(length, used)
            val firstPart = minOf(transferred, data.size - readIndex)

            data.copyInto(
                destination = destination,
                destinationOffset = offset,
                startIndex = readIndex,
                endIndex = readIndex + firstPart,
            )

            val secondPart = transferred - firstPart
            if (secondPart != 0) {
                data.copyInto(
                    destination = destination,
                    destinationOffset = offset + firstPart,
                    startIndex = 0,
                    endIndex = secondPart,
                )
            }

            readIndex = (readIndex + transferred) and mask
            used -= transferred
            transferred
        }
    }

    fun clear() {
        lock.withLock {
            readIndex = 0
            writeIndex = 0
            used = 0
        }
    }
}
