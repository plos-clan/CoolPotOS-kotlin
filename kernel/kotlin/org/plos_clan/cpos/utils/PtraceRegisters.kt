package org.plos_clan.cpos.utils

import kotlinx.cinterop.*

@ExperimentalForeignApi
class PtraceRegisters(private val registers: CPointer<ULongVar>) {
    companion object {
        const val IDX_R15 = 0
        const val IDX_R14 = 1
        const val IDX_R13 = 2
        const val IDX_R12 = 3
        const val IDX_R11 = 4
        const val IDX_R10 = 5
        const val IDX_R9 = 6
        const val IDX_R8 = 7
        const val IDX_RBX = 8
        const val IDX_RCX = 9
        const val IDX_RDX = 10
        const val IDX_RSI = 11
        const val IDX_RDI = 12
        const val IDX_RBP = 13
        const val IDX_DS = 14
        const val IDX_ES = 15
        const val IDX_FS_BASE = 16
        const val IDX_RAX = 17
        const val IDX_FUNC = 18
        const val IDX_ERRCODE = 19
        const val IDX_RIP = 20
        const val IDX_CS = 21
        const val IDX_RFLAGS = 22
        const val IDX_RSP = 23
        const val IDX_SS = 24
        const val REGISTER_COUNT = IDX_SS + 1
    }

    operator fun get(index: Int): ULong =
        if (index in 0 until REGISTER_COUNT) {
            registers[index]
        } else {
            0uL
        }

    operator fun set(index: Int, value: ULong) {
        if (index in 0 until REGISTER_COUNT) {
            registers[index] = value
        }
    }

    fun copyInto(destination: ULongArray) {
        val count = minOf(destination.size, REGISTER_COUNT)
        for (index in 0 until count) {
            destination[index] = registers[index]
        }
    }

    fun restoreFrom(source: ULongArray) {
        val count = minOf(source.size, REGISTER_COUNT)
        for (index in 0 until count) {
            registers[index] = source[index]
        }
    }

    val r15: ULong get() = registers[IDX_R15]
    val r14: ULong get() = registers[IDX_R14]
    val r13: ULong get() = registers[IDX_R13]
    val r12: ULong get() = registers[IDX_R12]
    val r11: ULong get() = registers[IDX_R11]
    val r10: ULong get() = registers[IDX_R10]
    val r9: ULong get() = registers[IDX_R9]
    val r8: ULong get() = registers[IDX_R8]
    val rbx: ULong get() = registers[IDX_RBX]
    val rcx: ULong get() = registers[IDX_RCX]
    val rdx: ULong get() = registers[IDX_RDX]
    val rsi: ULong get() = registers[IDX_RSI]
    val rdi: ULong get() = registers[IDX_RDI]
    val rbp: ULong get() = registers[IDX_RBP]

    val ds: ULong get() = registers[IDX_DS]
    val es: ULong get() = registers[IDX_ES]

    val fsBase: ULong get() = registers[IDX_FS_BASE]
    val rax: ULong get() = registers[IDX_RAX]
    val func: ULong get() = registers[IDX_FUNC]
    val errcode: ULong get() = registers[IDX_ERRCODE]

    val rip: ULong get() = registers[IDX_RIP]
    val cs: ULong get() = registers[IDX_CS]
    val rflags: ULong get() = registers[IDX_RFLAGS]
    val rsp: ULong get() = registers[IDX_RSP]
    val ss: ULong get() = registers[IDX_SS]
}
