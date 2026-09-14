package org.plos_clan.cpos.utils

abstract class CriticalSection {
    @PublishedApi
    internal abstract fun acquire(): ULong

    @PublishedApi
    internal abstract fun release(state: ULong)

    inline fun <T> withLock(block: () -> T): T {
        val state = acquire()
        return try {
            block()
        } finally {
            release(state)
        }
    }
}
