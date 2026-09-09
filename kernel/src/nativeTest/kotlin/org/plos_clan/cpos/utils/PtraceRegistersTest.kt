@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertContentEquals

class PtraceRegistersTest {
    @Test
    fun execCreatesCleanUserEntryContext() = memScoped {
        val registers = PtraceRegisters(allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT))
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
    }
}
