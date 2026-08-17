package org.plos_clan.cpos.fs

import org.plos_clan.cpos.drivers.TscClock

data class VfsTimestamp(
    val seconds: Long,
    val nanoseconds: UInt,
) : Comparable<VfsTimestamp> {
    init {
        require(nanoseconds < NANOSECONDS_PER_SECOND)
    }

    override fun compareTo(other: VfsTimestamp): Int {
        val secondsComparison = seconds.compareTo(other.seconds)
        return if (secondsComparison != 0) secondsComparison
        else nanoseconds.compareTo(other.nanoseconds)
    }

    internal fun isOlderThan(reference: VfsTimestamp, seconds: Long): Boolean {
        if (this.seconds > Long.MAX_VALUE - seconds) return false
        val threshold = this.seconds + seconds
        return threshold < reference.seconds ||
            threshold == reference.seconds && nanoseconds <= reference.nanoseconds
    }

    companion object {
        internal const val NANOSECONDS_PER_SECOND = 1_000_000_000u

        fun now(): VfsTimestamp {
            val nanoseconds = TscClock.nanoTime()
            return VfsTimestamp(
                seconds = (nanoseconds / NANOSECONDS_PER_SECOND).toLong(),
                nanoseconds = (nanoseconds % NANOSECONDS_PER_SECOND).toUInt(),
            )
        }
    }
}

data class InodeTimestamps(
    val accessTime: VfsTimestamp,
    val modificationTime: VfsTimestamp,
    val changeTime: VfsTimestamp,
    val birthTime: VfsTimestamp?,
) {
    companion object {
        fun now(): InodeTimestamps = VfsTimestamp.now().let { timestamp ->
            InodeTimestamps(timestamp, timestamp, timestamp, timestamp)
        }

        fun fromModificationTime(timestamp: VfsTimestamp): InodeTimestamps =
            InodeTimestamps(timestamp, timestamp, timestamp, null)
    }
}

sealed interface InodeTimestampUpdate {
    val requiresCurrentTime: Boolean
    fun apply(timestamps: InodeTimestamps, now: VfsTimestamp): InodeTimestamps
}

enum class InodeTimestampEvent : InodeTimestampUpdate {
    NONE,
    ACCESSED,
    RELATIVE_ACCESS,
    CONTENT_CHANGED,
    STATUS_CHANGED;

    override val requiresCurrentTime: Boolean
        get() = this != NONE

    override fun apply(timestamps: InodeTimestamps, now: VfsTimestamp): InodeTimestamps =
        when (this) {
            NONE -> timestamps
            ACCESSED -> timestamps.copy(accessTime = now)
            RELATIVE_ACCESS -> if (
                timestamps.accessTime <= timestamps.modificationTime ||
                timestamps.accessTime <= timestamps.changeTime ||
                timestamps.accessTime.isOlderThan(now, RELATIVE_ATIME_INTERVAL_SECONDS)
            ) {
                timestamps.copy(accessTime = now)
            } else {
                timestamps
            }
            CONTENT_CHANGED -> timestamps.copy(modificationTime = now, changeTime = now)
            STATUS_CHANGED -> timestamps.copy(changeTime = now)
        }

    private companion object {
        const val RELATIVE_ATIME_INTERVAL_SECONDS = 24L * 60L * 60L
    }
}

data class InodeTimestampSet(
    val accessTime: Value,
    val modificationTime: Value,
) : InodeTimestampUpdate {
    sealed interface Value {
        data object Now : Value
        data object Omit : Value
        data class Exact(val value: VfsTimestamp) : Value

        fun resolve(current: VfsTimestamp, now: VfsTimestamp): VfsTimestamp = when (this) {
            Now -> now
            Omit -> current
            is Exact -> value
        }
    }

    val omitsBoth: Boolean
        get() = accessTime == Value.Omit && modificationTime == Value.Omit

    val setsBothToNow: Boolean
        get() = accessTime == Value.Now && modificationTime == Value.Now

    override val requiresCurrentTime: Boolean
        get() = !omitsBoth

    override fun apply(timestamps: InodeTimestamps, now: VfsTimestamp): InodeTimestamps =
        if (omitsBoth) {
            timestamps
        } else {
            timestamps.copy(
                accessTime = accessTime.resolve(timestamps.accessTime, now),
                modificationTime = modificationTime.resolve(timestamps.modificationTime, now),
                changeTime = now,
            )
        }

    companion object {
        val NOW = InodeTimestampSet(Value.Now, Value.Now)
    }
}
