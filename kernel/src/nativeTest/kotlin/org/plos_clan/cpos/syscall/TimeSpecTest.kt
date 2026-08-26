package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
