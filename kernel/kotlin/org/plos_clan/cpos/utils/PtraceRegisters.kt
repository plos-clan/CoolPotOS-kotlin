package org.plos_clan.cpos.utils

import kotlinx.cinterop.*

@ExperimentalForeignApi
class PtraceRegisters(private val registers: CPointer<ULongVar>) {
    companion object {
        const val REGISTER_COUNT = 24
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
        const val IDX_RAX = 16
        const val IDX_FUNC = 17
        const val IDX_ERRCODE = 18
        const val IDX_RIP = 19
        const val IDX_CS = 20
        const val IDX_RFLAGS = 21
        const val IDX_RSP = 22
        const val IDX_SS = 23
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

    val r15: ULong get() = registers[0]
    val r14: ULong get() = registers[1]
    val r13: ULong get() = registers[2]
    val r12: ULong get() = registers[3]
    val r11: ULong get() = registers[4]
    val r10: ULong get() = registers[5]
    val r9: ULong get() = registers[6]
    val r8: ULong get() = registers[7]
    val rbx: ULong get() = registers[8]
    val rcx: ULong get() = registers[9]
    val rdx: ULong get() = registers[10]
    val rsi: ULong get() = registers[11]
    val rdi: ULong get() = registers[12]
    val rbp: ULong get() = registers[13]

    val ds: ULong get() = registers[14]
    val es: ULong get() = registers[15]

    val rax: ULong get() = registers[16]
    val func: ULong get() = registers[17]
    val errcode: ULong get() = registers[18]

    val rip: ULong get() = registers[19]
    val cs: ULong get() = registers[20]
    val rflags: ULong get() = registers[21]
    val rsp: ULong get() = registers[22]
    val ss: ULong get() = registers[23]
}
