package org.plos_clan.cpos.time

internal data class DateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    fun toEpochSeconds(): Long? {
        if (year !in 1970..9999 || month !in 1..12 ||
            hour !in 0..23 || minute !in 0..59 || second !in 0..59
        ) {
            return null
        }
        val leapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val monthLength = MONTH_LENGTHS[month - 1] +
            if (month == 2 && leapYear) 1 else 0
        if (day !in 1..monthLength) return null

        val precedingYear = year - 1
        val leapDays = precedingYear / 4 - precedingYear / 100 + precedingYear / 400 -
            (1969 / 4 - 1969 / 100 + 1969 / 400)
        val precedingMonths = MONTH_STARTS[month - 1] +
            if (month > 2 && leapYear) 1 else 0
        val days = (year - 1970).toLong() * 365 + leapDays.toLong() +
            precedingMonths.toLong() + day - 1
        return days * SECONDS_PER_DAY + hour.toLong() * SECONDS_PER_HOUR +
            minute.toLong() * SECONDS_PER_MINUTE + second.toLong()
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
        const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
        val MONTH_LENGTHS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        val MONTH_STARTS = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    }
}
