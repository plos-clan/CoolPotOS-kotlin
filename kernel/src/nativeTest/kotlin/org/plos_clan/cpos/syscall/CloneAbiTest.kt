@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CloneAbiTest {
    @Test
    fun pidfdAndTidOutputsRemainIndependentAcrossArgumentVersions() {
        for (size in listOf(64, 80, 88)) {
            val bytes = ByteArray(size)
            LittleEndianBuffer(bytes).apply {
                writeU64(0, 0x0110_1000uL) // PIDFD | PARENT_SETTID | CHILD_SETTID
                writeU64(8, 0x1_0000_1000uL)
                writeU64(16, 0x1_0000_2000uL)
                writeU64(24, 0x1_0000_3000uL)
                writeU64(32, 17uL)
                writeU64(40, 0x4000uL)
                writeU64(48, 0x2000uL)
                writeU64(56, 0x7000uL)
            }
            val request = assertNotNull(CloneRequest.decode(bytes))
            assertNull(request.validate())
            assertEquals(0x1_0000_1000uL, request.pidfd)
            assertEquals(0x1_0000_2000uL, request.childTid)
            assertEquals(0x1_0000_3000uL, request.parentTid)
            assertEquals(0x6000uL, request.stackPointer)
            assertEquals(0x7000uL, request.tls)
        }
    }

    @Test
    fun aliasedParentOutputsAreRejectedOnlyWhenBothAreRequested() {
        val bytes = ByteArray(64)
        val input = LittleEndianBuffer(bytes)
        input.writeU64(8, 0x1000uL)
        input.writeU64(24, 0x1000uL)
        for (flags in listOf(0uL, 0x1000uL, 0x10_0000uL, 0x10_1000uL)) {
            input.writeU64(0, flags)
            val request = assertNotNull(CloneRequest.decode(bytes))
            assertEquals(if (flags == 0x10_1000uL) Errno.EINVAL else null, request.validate())
        }
    }

    @Test
    fun threadPidfdsAreSupportedAndDetachedRemainsInvalid() {
        val bytes = ByteArray(64)
        val input = LittleEndianBuffer(bytes)
        input.writeU64(0, 0x1_1f00uL) // THREAD | PIDFD | SIGHAND | FILES | FS | VM
        assertNull(assertNotNull(CloneRequest.decode(bytes)).validate())
        input.writeU64(32, 17uL)
        assertEquals(Errno.EINVAL, assertNotNull(CloneRequest.decode(bytes)).validate())
        input.writeU64(0, 0x40_1000uL) // DETACHED | PIDFD
        assertEquals(Errno.EINVAL, assertNotNull(CloneRequest.decode(bytes)).validate())
    }

    @Test
    fun legacyCloneUsesTheParentTidSlotForPidfd() = memScoped {
        val registers = PtraceRegisters(allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT))
        repeat(PtraceRegisters.REGISTER_COUNT) { registers[it] = 0uL }
        registers[PtraceRegisters.IDX_RDI] = 0x1011uL
        registers[PtraceRegisters.IDX_RDX] = 0x1_0000_1234uL
        val request = CloneRequest.legacy(registers)
        assertEquals(17uL, request.exitSignal)
        assertEquals(0x1000uL, request.flags)
        assertEquals(0x1_0000_1234uL, request.pidfd)
        assertNull(request.validate())
        registers[PtraceRegisters.IDX_RDI] = 0x10_1011uL
        assertEquals(Errno.EINVAL, CloneRequest.legacy(registers).validate())
    }

    @Test
    fun decodingStillValidatesStackBoundsAndPartialExtensions() {
        assertNull(CloneRequest.decode(ByteArray(63)))
        val bytes = ByteArray(88)
        val input = LittleEndianBuffer(bytes)
        input.writeU64(0, 0x1000uL)
        input.writeU64(40, 0x1000uL)
        assertNull(CloneRequest.decode(bytes))
        input.writeU64(48, ULong.MAX_VALUE)
        assertNull(CloneRequest.decode(bytes))
        input.writeU64(40, 0uL)
        input.writeU64(48, 0uL)
        input.writeU64(72, 1uL)
        for (size in 73..88) assertNull(CloneRequest.decode(bytes.copyOf(size)))
        input.writeU64(72, 0uL)
        input.writeU64(0, 0x2_0000_1000uL) // INTO_CGROUP | PIDFD
        for (size in 64..87) {
            assertEquals(Errno.EINVAL, assertNotNull(CloneRequest.decode(bytes.copyOf(size))).validate())
        }
        input.writeU64(80, 7uL)
        val request = assertNotNull(CloneRequest.decode(bytes))
        assertNull(request.validate())
        assertEquals(7uL, request.cgroup)
    }
}
