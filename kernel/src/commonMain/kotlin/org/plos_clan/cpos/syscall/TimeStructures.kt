package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.MutableNativeStruct
import org.plos_clan.cpos.utils.NativeStruct

internal data class TimeSpec(var sec: Long, var nsec: Long) : MutableNativeStruct {
    override val nativeSize: Int get() = NATIVE_SIZE

    override fun writeTo(output: LittleEndianBuffer) = writeTo(output, 0)

    override fun readFrom(input: LittleEndianBuffer) {
        sec = input.readU64(SEC_OFFSET).toLong()
        nsec = input.readU64(NSEC_OFFSET).toLong()
    }

    val isValidDuration: Boolean
        get() = sec >= 0 && nsec in 0 until NANOSECONDS_PER_SECOND.toLong()

    val isZeroDuration: Boolean
        get() = sec == 0L && nsec == 0L

    val durationNanos: ULong
        get() {
            require(isValidDuration)
            val seconds = sec.toULong()
            val nanoseconds = nsec.toULong()
            return if (seconds > (ULong.MAX_VALUE - nanoseconds) / NANOSECONDS_PER_SECOND) {
                ULong.MAX_VALUE
            } else {
                seconds * NANOSECONDS_PER_SECOND + nanoseconds
            }
        }

    fun deadlineFrom(nowNanos: ULong): ULong {
        val duration = durationNanos
        return if (duration > ULong.MAX_VALUE - nowNanos) ULong.MAX_VALUE
        else nowNanos + duration
    }

    internal fun writeTo(output: LittleEndianBuffer, offset: Int) {
        output.writeU64(offset + SEC_OFFSET, sec.toULong())
        output.writeU64(offset + NSEC_OFFSET, nsec.toULong())
    }

    companion object {
        private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
        private const val SEC_OFFSET = 0
        private const val NSEC_OFFSET = SEC_OFFSET + Long.SIZE_BYTES
        const val NATIVE_SIZE = Long.SIZE_BYTES * 2

        fun fromDurationNanos(nanoseconds: ULong): TimeSpec = TimeSpec(
            sec = (nanoseconds / NANOSECONDS_PER_SECOND).toLong(),
            nsec = (nanoseconds % NANOSECONDS_PER_SECOND).toLong(),
        )

        internal fun readFrom(input: LittleEndianBuffer, offset: Int = 0): TimeSpec = TimeSpec(
            sec = input.readU64(offset + SEC_OFFSET).toLong(),
            nsec = input.readU64(offset + NSEC_OFFSET).toLong(),
        )
    }
}

internal data class IntervalTimerSpec(
    val interval: TimeSpec,
    val value: TimeSpec,
) : NativeStruct {
    constructor(intervalNanos: ULong, valueNanos: ULong) : this(
        TimeSpec.fromDurationNanos(intervalNanos),
        TimeSpec.fromDurationNanos(valueNanos),
    )

    override val nativeSize: Int get() = NATIVE_SIZE

    override fun writeTo(output: LittleEndianBuffer) {
        interval.writeTo(output, 0)
        value.writeTo(output, TimeSpec.NATIVE_SIZE)
    }

    companion object {
        const val NATIVE_SIZE = TimeSpec.NATIVE_SIZE * 2

        fun fromNativeBytes(bytes: ByteArray): IntervalTimerSpec? {
            if (bytes.size != NATIVE_SIZE) return null
            return readFrom(LittleEndianBuffer(bytes))
        }

        fun readFrom(input: LittleEndianBuffer) = IntervalTimerSpec(
            TimeSpec.readFrom(input, 0),
            TimeSpec.readFrom(input, TimeSpec.NATIVE_SIZE),
        )
    }
}
