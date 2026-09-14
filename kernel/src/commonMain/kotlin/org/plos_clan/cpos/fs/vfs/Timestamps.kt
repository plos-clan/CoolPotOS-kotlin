package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.time.Instant

data class InodeTimestamps(
    val accessTime: Instant,
    val modificationTime: Instant,
    val changeTime: Instant,
    val birthTime: Instant?,
) {
    companion object {
        fun at(timestamp: Instant): InodeTimestamps =
            InodeTimestamps(timestamp, timestamp, timestamp, timestamp)

        fun fromModificationTime(timestamp: Instant): InodeTimestamps =
            InodeTimestamps(timestamp, timestamp, timestamp, null)
    }
}

sealed interface InodeTimestampUpdate {
    val requiresCurrentTime: Boolean
    fun apply(timestamps: InodeTimestamps, now: Instant): InodeTimestamps
}

enum class InodeTimestampEvent : InodeTimestampUpdate {
    NONE,
    ACCESSED,
    RELATIVE_ACCESS,
    CONTENT_CHANGED,
    STATUS_CHANGED;

    override val requiresCurrentTime: Boolean
        get() = this != NONE

    override fun apply(timestamps: InodeTimestamps, now: Instant): InodeTimestamps =
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
        data class Exact(val value: Instant) : Value

        fun resolve(current: Instant, now: Instant): Instant = when (this) {
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

    override fun apply(timestamps: InodeTimestamps, now: Instant): InodeTimestamps =
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
