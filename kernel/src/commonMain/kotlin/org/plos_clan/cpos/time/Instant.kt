package org.plos_clan.cpos.time

data class Instant(
    val seconds: Long,
    val nanoseconds: UInt,
) : Comparable<Instant> {
    init {
        require(nanoseconds.toULong() < NANOSECONDS_PER_SECOND)
    }

    override fun compareTo(other: Instant): Int {
        val secondsComparison = seconds.compareTo(other.seconds)
        return if (secondsComparison != 0) secondsComparison else nanoseconds.compareTo(other.nanoseconds)
    }

    internal fun isOlderThan(reference: Instant, seconds: Long): Boolean {
        if (this.seconds > Long.MAX_VALUE - seconds) return false
        val threshold = this.seconds + seconds
        return threshold < reference.seconds || threshold == reference.seconds && nanoseconds <= reference.nanoseconds
    }

    fun toNanoseconds(): ULong {
        if (seconds <= 0L) return if (seconds == 0L) nanoseconds.toULong() else 0uL
        val wholeSeconds = seconds.toULong()
        return if (wholeSeconds >
            (ULong.MAX_VALUE - nanoseconds.toULong()) / NANOSECONDS_PER_SECOND
        ) {
            ULong.MAX_VALUE
        } else {
            wholeSeconds * NANOSECONDS_PER_SECOND + nanoseconds.toULong()
        }
    }

    fun durationUntil(seconds: Long, nanoseconds: UInt): ULong {
        if (seconds < this.seconds ||
            seconds == this.seconds && nanoseconds <= this.nanoseconds
        ) {
            return 0uL
        }

        var wholeSeconds = (seconds - this.seconds).toULong()
        val fractional = if (nanoseconds >= this.nanoseconds) {
            (nanoseconds - this.nanoseconds).toULong()
        } else {
            wholeSeconds--
            NANOSECONDS_PER_SECOND + nanoseconds.toULong() - this.nanoseconds.toULong()
        }
        return if (wholeSeconds > (ULong.MAX_VALUE - fractional) / NANOSECONDS_PER_SECOND) {
            ULong.MAX_VALUE
        } else {
            wholeSeconds * NANOSECONDS_PER_SECOND + fractional
        }
    }
    companion object {
        internal const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
    }
}
