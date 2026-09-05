package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PidFdSignalAbiTest {
    @Test
    fun flagsSelectExactlyOneScope() {
        assertEquals(PidFdSignalScope.DEFAULT, PidFdSignalScope.from(0uL))
        assertEquals(PidFdSignalScope.THREAD, PidFdSignalScope.from(1uL))
        assertEquals(PidFdSignalScope.THREAD_GROUP, PidFdSignalScope.from(2uL))
        assertEquals(PidFdSignalScope.PROCESS_GROUP, PidFdSignalScope.from(4uL))
        for (flags in listOf(3uL, 5uL, 6uL, 7uL, 8uL, 0x8000_0000uL, ULong.MAX_VALUE)) {
            assertNull(PidFdSignalScope.from(flags))
        }
    }

    @Test
    fun flagsUseTheUnsignedIntSyscallAbi() {
        assertEquals(PidFdSignalScope.DEFAULT, PidFdSignalScope.from(0xffff_ffff_0000_0000uL))
        assertEquals(PidFdSignalScope.THREAD, PidFdSignalScope.from(0x1234_5678_0000_0001uL))
        assertNull(PidFdSignalScope.from(0x1234_5678_0000_0003uL))
    }

    @Test
    fun descriptorScopeIsOnlyUsedWhenFlagsAreZero() {
        assertEquals(
            PidFdSignalScope.THREAD_GROUP,
            PidFdSignalScope.DEFAULT.resolve(PidHandle.Scope.PROCESS),
        )
        assertEquals(
            PidFdSignalScope.THREAD,
            PidFdSignalScope.DEFAULT.resolve(PidHandle.Scope.THREAD),
        )
        for (scope in PidFdSignalScope.entries.filter { it != PidFdSignalScope.DEFAULT }) {
            for (descriptorScope in PidHandle.Scope.entries) {
                assertEquals(scope, scope.resolve(descriptorScope))
            }
        }
    }

    @Test
    fun queuedInfoPreservesTheSignalNumberAndOpaquePayload() {
        val bytes = ByteArray(SignalAbi.INFO_SIZE) { it.toByte() }
        LittleEndianBuffer(bytes).apply {
            writeU32(0, 35u)
            writeU32(4, (-7).toUInt())
            writeU32(8, SignalInfo.QUEUED.toUInt())
        }
        val supplied = SignalAbi.readQueuedInfo(bytes)
        assertEquals(35, supplied.number)
        assertEquals(-7, supplied.error)
        assertFalse(supplied.requiresSelf)

        val info = supplied.signalInfo(checkNotNull(Signal.from(supplied.number)))
        assertEquals(SignalInfo.QUEUED, info.code)
        assertEquals(-7, info.error)
        assertContentEquals(bytes.copyOfRange(16, bytes.size), (info.payload as SignalPayload.Raw).bytes)
        val encoded = SignalAbi.infoBytes(info)
        assertContentEquals(bytes.copyOfRange(0, 12), encoded.copyOfRange(0, 12))
        assertTrue(encoded.copyOfRange(12, 16).all { it == 0.toByte() })
        assertContentEquals(bytes.copyOfRange(16, bytes.size), encoded.copyOfRange(16, encoded.size))
    }

    @Test
    fun zeroAndInvalidSignalNumbersArePreservedForValidation() {
        for (number in listOf(0, -1, Signal.MAX + 1, Int.MIN_VALUE)) {
            val bytes = ByteArray(SignalAbi.INFO_SIZE)
            LittleEndianBuffer(bytes).writeU32(0, number.toUInt())
            assertEquals(number, SignalAbi.readQueuedInfo(bytes).number)
        }
    }

    @Test
    fun userAndThreadCodesCannotBeForgedForOtherTasks() {
        val bytes = ByteArray(SignalAbi.INFO_SIZE)
        val input = LittleEndianBuffer(bytes)
        for (code in listOf(SignalInfo.USER, SignalInfo.THREAD, SignalInfo.KERNEL, Int.MAX_VALUE)) {
            input.writeU32(8, code.toUInt())
            assertTrue(SignalAbi.readQueuedInfo(bytes).requiresSelf)
        }
        for (code in listOf(SignalInfo.QUEUED, -2, -3, Int.MIN_VALUE)) {
            input.writeU32(8, code.toUInt())
            assertFalse(SignalAbi.readQueuedInfo(bytes).requiresSelf)
        }
    }
}
