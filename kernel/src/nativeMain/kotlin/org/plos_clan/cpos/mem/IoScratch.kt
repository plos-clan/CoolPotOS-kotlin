package org.plos_clan.cpos.mem

import kotlin.native.concurrent.ThreadLocal

internal class IoScratch private constructor() {
    private var next: IoScratch? = null
    var bytes = ByteArray(0)
        private set

    fun reserve(size: Int): ByteArray {
        if (size > bytes.size) {
            val doubled = bytes.size.toLong() * 2
            val capacity = maxOf(size.toLong(), doubled).coerceAtMost(Int.MAX_VALUE.toLong())
            bytes = bytes.copyOf(capacity.toInt())
        }
        return bytes
    }

    @ThreadLocal
    companion object {
        private var available: IoScratch? = null

        inline fun <T> borrow(block: (IoScratch) -> T): T {
            val scratch = available ?: IoScratch()
            available = scratch.next
            return try {
                block(scratch)
            } finally {
                scratch.next = available
                available = scratch
            }
        }

        inline fun <T> use(size: Int, block: (ByteArray) -> T): T =
            borrow { block(it.reserve(size)) }
    }
}
