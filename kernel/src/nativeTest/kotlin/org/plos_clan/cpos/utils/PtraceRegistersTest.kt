@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PtraceRegistersTest {
    @Test
    fun registerViewsWriteThroughWithinFrameBounds() = memScoped {
        val count = PtraceRegisters.REGISTER_COUNT
        val storage = allocArray<ULongVar>(count + 2)
        repeat(count + 2) { storage[it] = ULong.MAX_VALUE }
        val frame = requireNotNull(storage + 1)
        val registers = PtraceRegisters(frame)
        val other = PtraceRegisters(frame)

        repeat(count) { index ->
            registers[index] = index.toULong()
            assertEquals(index.toULong(), other[index])
            assertEquals(index.toULong(), storage[index + 1])
        }
        for (index in listOf(Int.MIN_VALUE, -1, count, Int.MAX_VALUE)) {
            registers[index] = 0uL
            assertEquals(0uL, registers[index])
        }
        assertEquals(ULong.MAX_VALUE, storage[0])
        assertEquals(ULong.MAX_VALUE, storage[count + 1])
    }

    @Test
    fun execCreatesCleanUserEntryContext() = memScoped {
        val frame = alloc<bridge.kernel_entry_frame_t>()
        val registers = PtraceRegisters(frame.ptr.reinterpret())
        repeat(PtraceRegisters.REGISTER_COUNT) { registers[it] = ULong.MAX_VALUE }

        registers.resetForExec(0x1234uL, 0x7fff_ffff_f000uL)

        val expected = ULongArray(PtraceRegisters.REGISTER_COUNT)
        expected[PtraceRegisters.IDX_DS] = 0x1buL
        expected[PtraceRegisters.IDX_ES] = 0x1buL
        expected[PtraceRegisters.IDX_RIP] = 0x1234uL
        expected[PtraceRegisters.IDX_CS] = 0x23uL
        expected[PtraceRegisters.IDX_RFLAGS] = 0x202uL
        expected[PtraceRegisters.IDX_RSP] = 0x7fff_ffff_f000uL
        expected[PtraceRegisters.IDX_SS] = 0x1buL
        val actual = ULongArray(PtraceRegisters.REGISTER_COUNT)
        registers.copyInto(actual)

        assertContentEquals(expected, actual)
        assertEquals(0uL, frame.xstate.header.state_bv)
        assertEquals(0x1f80u, frame.xstate.legacy.mxcsr)
    }

    @Test
    fun signalSnapshotUsesOnlyLiveXstateComponents() = memScoped {
        val frame = alloc<bridge.kernel_entry_frame_t>()
        val registers = PtraceRegisters(frame.ptr.reinterpret())
        val image = ByteArray(PtraceRegisters.EXTENDED_STATE_SIZE) { 0x55 }
        val output = ByteArray(image.size + 2) { 0x33 }
        val fields = LittleEndianBuffer(image)
        val header = bridge.xstate_header_offset.toInt()
        for (active in 0uL..7uL) {
            fields.writeU64(header, active)
            registers.restoreExtendedState(image)
            registers.copyExtendedStateTo(output, 1)
            val saved = LittleEndianBuffer(output)
            assertEquals(active, saved.readU64(1 + header))
            assertEquals(if (active and 1uL != 0uL) 0x5555 else 0x037f, saved.readU16(1).toInt())
            assertEquals(0x55555555u, saved.readU32(25))
            assertEquals(if (active and 2uL != 0uL) 0x55 else 0, output[161].toInt())
            assertEquals(if (active and 4uL != 0uL) 0x55 else 0, output[577].toInt())
            assertEquals(0, output[417].toInt())
            assertEquals(0x33, output.first().toInt())
            assertEquals(0x33, output.last().toInt())
        }
    }
}
