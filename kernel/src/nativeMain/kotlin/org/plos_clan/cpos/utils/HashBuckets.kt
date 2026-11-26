package org.plos_clan.cpos.utils

internal class HashBuckets<T>(size: Int, create: () -> T) : Iterable<T> {
    private val buckets = List(size.coerceAtLeast(1)) { create() }

    operator fun get(key: Any): T {
        val hash = key.hashCode().toUInt() * 0x9e37_79b9u
        val index = (hash.toULong() * buckets.size.toUInt()) shr UInt.SIZE_BITS
        return buckets[index.toInt()]
    }

    override fun iterator(): Iterator<T> = buckets.iterator()
}
