package org.plos_clan.cpos.time

fun interface MonotonicClock {
    fun nanoTime(): ULong

    fun deadlineAfterMillis(delay: Long): ULong {
        val now = nanoTime()
        val millis = delay.coerceAtLeast(0).toULong()
        val scale = 1_000_000uL
        return if (millis > (ULong.MAX_VALUE - now) / scale) ULong.MAX_VALUE else now + millis * scale
    }
}
