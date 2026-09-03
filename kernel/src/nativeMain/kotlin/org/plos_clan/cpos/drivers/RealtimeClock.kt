@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import bridge.asm_pause
import bridge.io_in8
import bridge.io_out8
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.acpi.fadt.Fadt
import org.plos_clan.cpos.utils.IrqSpinLock

object RealtimeClock {
    data class Instant(
        val seconds: Long,
        val nanoseconds: UInt,
    ) {
        init {
            require(nanoseconds.toULong() < NANOSECONDS_PER_SECOND)
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
    }

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
            val MONTH_LENGTHS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val MONTH_STARTS = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        }
    }

    private data class Anchor(
        val epochSeconds: Long,
        val monotonicNanoseconds: ULong,
    )

    private data class Snapshot(
        val second: UInt,
        val minute: UInt,
        val hour: UInt,
        val day: UInt,
        val month: UInt,
        val year: UInt,
        val century: UInt?,
        val statusB: UInt,
    )

    private val lock = IrqSpinLock()
    private var anchor: Anchor? = null

    fun initialize() {
        if (anchor != null || !TscClock.isReady) return
        val dateTime = readStableDateTime(Fadt.info?.centuryRegister)
        val epochSeconds = dateTime?.toEpochSeconds()
        if (dateTime == null || epochSeconds == null) {
            println("RTC: CMOS time is unavailable; CLOCK_REALTIME starts at the Unix epoch")
            return
        }

        anchor = Anchor(epochSeconds, TscClock.nanoTime())
        println(
            "RTC: clocksource=cmos realtime=" +
                "${dateTime.year.toString().padStart(4, '0')}-" +
                "${dateTime.month.toString().padStart(2, '0')}-" +
                "${dateTime.day.toString().padStart(2, '0')}T" +
                "${dateTime.hour.toString().padStart(2, '0')}:" +
                "${dateTime.minute.toString().padStart(2, '0')}:" +
                "${dateTime.second.toString().padStart(2, '0')}Z",
        )
    }

    fun now(): Instant = atMonotonic(TscClock.nanoTime())

    internal fun atMonotonic(monotonic: ULong): Instant {
        val reference = anchor ?: return Instant(
            (monotonic / NANOSECONDS_PER_SECOND).toLong(),
            (monotonic % NANOSECONDS_PER_SECOND).toUInt(),
        )
        val elapsed = monotonic - reference.monotonicNanoseconds
        val seconds = elapsed / NANOSECONDS_PER_SECOND
        val epochSeconds = if (seconds > (Long.MAX_VALUE - reference.epochSeconds).toULong()) {
            Long.MAX_VALUE
        } else {
            reference.epochSeconds + seconds.toLong()
        }
        return Instant(epochSeconds, (elapsed % NANOSECONDS_PER_SECOND).toUInt())
    }

    private fun readStableDateTime(centuryRegister: UInt?): DateTime? = lock.withLock {
        try {
            repeat(MAX_READ_ATTEMPTS) sample@ {
                var polls = 0
                while (readRegister(STATUS_A) and UPDATE_IN_PROGRESS != 0u) {
                    if (++polls == UPDATE_POLL_ATTEMPTS) return@withLock null
                    asm_pause()
                }

                val first = readSnapshot(centuryRegister)
                if (readRegister(STATUS_A) and UPDATE_IN_PROGRESS != 0u) return@sample
                val second = readSnapshot(centuryRegister)
                if (readRegister(STATUS_A) and UPDATE_IN_PROGRESS == 0u && first == second) {
                    return@withLock decode(second)
                }
            }
            null
        } finally {
            io_out8(INDEX_PORT, 0u.toUByte())
        }
    }

    private fun readSnapshot(centuryRegister: UInt?) = Snapshot(
        second = readRegister(SECONDS),
        minute = readRegister(MINUTES),
        hour = readRegister(HOURS),
        day = readRegister(DAY_OF_MONTH),
        month = readRegister(MONTH),
        year = readRegister(YEAR),
        century = centuryRegister?.takeIf { it <= REGISTER_MASK }?.let(::readRegister),
        statusB = readRegister(STATUS_B),
    )

    private fun decode(snapshot: Snapshot): DateTime? {
        val binary = snapshot.statusB and BINARY_MODE != 0u
        val second = decode(snapshot.second, binary) ?: return null
        val minute = decode(snapshot.minute, binary) ?: return null
        val pm = snapshot.hour and PM != 0u
        val rawHour = decode(snapshot.hour and PM.inv(), binary) ?: return null
        val hour = if (snapshot.statusB and HOUR_24 != 0u) {
            rawHour
        } else {
            if (rawHour !in 1..12) return null
            rawHour % 12 + if (pm) 12 else 0
        }
        val day = decode(snapshot.day, binary) ?: return null
        val month = decode(snapshot.month, binary) ?: return null
        val year = decode(snapshot.year, binary) ?: return null
        val century = snapshot.century?.let { decode(it, binary) }
        val fullYear = century?.takeIf { it in 19..99 }?.let { it * 100 + year }
            ?: if (year >= 70) 1900 + year else 2000 + year
        return DateTime(fullYear, month, day, hour, minute, second)
    }

    private fun decode(value: UInt, binary: Boolean): Int? {
        if (binary) return value.toInt()
        val low = value and 0x0fu
        val high = value shr 4
        return if (low <= 9u && high <= 9u) (high * 10u + low).toInt() else null
    }

    private fun readRegister(register: UInt): UInt {
        io_out8(INDEX_PORT, (NMI_DISABLED or register).toUByte())
        return io_in8(DATA_PORT).toUInt()
    }

    private const val INDEX_PORT: UShort = 0x70u
    private const val DATA_PORT: UShort = 0x71u
    private const val NMI_DISABLED = 0x80u
    private const val REGISTER_MASK = 0x7fu
    private const val SECONDS = 0x00u
    private const val MINUTES = 0x02u
    private const val HOURS = 0x04u
    private const val DAY_OF_MONTH = 0x07u
    private const val MONTH = 0x08u
    private const val YEAR = 0x09u
    private const val STATUS_A = 0x0au
    private const val STATUS_B = 0x0bu
    private const val UPDATE_IN_PROGRESS = 0x80u
    private const val HOUR_24 = 0x02u
    private const val BINARY_MODE = 0x04u
    private const val PM = 0x80u
    private const val MAX_READ_ATTEMPTS = 8
    private const val UPDATE_POLL_ATTEMPTS = 100_000
    private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
    private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
}
