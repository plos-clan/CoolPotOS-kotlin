package org.plos_clan.cpos.time

fun interface MonotonicClock {
    fun nanoTime(): ULong
}
