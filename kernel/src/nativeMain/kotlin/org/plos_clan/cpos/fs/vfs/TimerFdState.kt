package org.plos_clan.cpos.fs.vfs

internal data class TimerFdSetting(
    val intervalNanos: ULong,
    val valueNanos: ULong,
)

internal class TimerFdState {
    var deadlineNanos: ULong? = null
        private set
    var intervalNanos: ULong = 0uL
        private set
    var expirations: ULong = 0uL
        private set

    fun replace(deadlineNanos: ULong?, intervalNanos: ULong) {
        this.deadlineNanos = deadlineNanos
        this.intervalNanos = intervalNanos
        expirations = 0uL
    }

    fun advance(nowNanos: ULong): Boolean {
        val deadline = deadlineNanos ?: return false
        if (nowNanos < deadline) return false

        val interval = intervalNanos
        val periods = if (interval == 0uL) {
            1uL
        } else {
            val elapsedPeriods = (nowNanos - deadline) / interval
            if (elapsedPeriods == ULong.MAX_VALUE) ULong.MAX_VALUE else elapsedPeriods + 1uL
        }
        expirations = if (periods > ULong.MAX_VALUE - expirations) {
            ULong.MAX_VALUE
        } else {
            expirations + periods
        }
        val nextDeadline = if (interval == 0uL ||
            periods > (ULong.MAX_VALUE - deadline) / interval
        ) {
            null
        } else {
            deadline + periods * interval
        }
        deadlineNanos = nextDeadline?.takeIf { it > nowNanos }
        return true
    }

    fun snapshot(nowNanos: ULong): TimerFdSetting {
        val deadline = deadlineNanos
        val remaining = if (deadline == null || deadline <= nowNanos) 0uL else deadline - nowNanos
        return TimerFdSetting(intervalNanos, remaining)
    }

    fun consume(): ULong = expirations.also { expirations = 0uL }
}
