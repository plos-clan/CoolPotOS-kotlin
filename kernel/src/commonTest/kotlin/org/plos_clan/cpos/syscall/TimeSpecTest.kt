package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeSpecTest {
    @Test
    fun serializesAndUpdatesSignedValues() {
        val original = TimeSpec(sec = -1, nsec = 999_999_999)
        val bytes = original.toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(TimeSpec.NATIVE_SIZE, bytes.size)
        assertEquals(-1L, input.readU64(0).toLong())
        assertEquals(999_999_999L, input.readU64(Long.SIZE_BYTES).toLong())

        val decoded = TimeSpec(0, 0)
        assertTrue(decoded.updateFromNativeBytes(bytes))
        assertEquals(original, decoded)
        assertContentEquals(bytes, decoded.toNativeBytes())
    }

    @Test
    fun rejectsWrongNativeSizeWithoutMutation() {
        val value = TimeSpec(1, 2)

        assertFalse(value.updateFromNativeBytes(ByteArray(TimeSpec.NATIVE_SIZE - 1)))
        assertEquals(TimeSpec(1, 2), value)
    }

    @Test
    fun convertsDurationsWithValidationAndSaturation() {
        val value = TimeSpec(12, 345)
        assertTrue(value.isValidDuration)
        assertEquals(12_000_000_345uL, value.durationNanos)
        assertEquals(value, TimeSpec.fromDurationNanos(value.durationNanos))
        assertEquals(12_000_000_355uL, value.deadlineFrom(10uL))

        assertFalse(TimeSpec(-1, 0).isValidDuration)
        assertFalse(TimeSpec(0, 1_000_000_000).isValidDuration)
        assertFailsWith<IllegalArgumentException> { TimeSpec(-1, 0).durationNanos }
        assertEquals(ULong.MAX_VALUE, TimeSpec(Long.MAX_VALUE, 999_999_999).durationNanos)
        assertEquals(ULong.MAX_VALUE, TimeSpec(0, 10).deadlineFrom(ULong.MAX_VALUE - 5uL))
    }

    @Test
    fun intervalTimerSpecUsesLinuxFieldOrder() {
        val original = IntervalTimerSpec(
            interval = TimeSpec(1, 2),
            value = TimeSpec(3, 4),
        )
        val bytes = original.toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(IntervalTimerSpec.NATIVE_SIZE, bytes.size)
        assertEquals(1uL, input.readU64(0))
        assertEquals(2uL, input.readU64(Long.SIZE_BYTES))
        assertEquals(3uL, input.readU64(TimeSpec.NATIVE_SIZE))
        assertEquals(4uL, input.readU64(TimeSpec.NATIVE_SIZE + Long.SIZE_BYTES))
        assertEquals(original, assertNotNull(IntervalTimerSpec.fromNativeBytes(bytes)))
        assertNull(IntervalTimerSpec.fromNativeBytes(ByteArray(bytes.size - 1)))
    }
}
