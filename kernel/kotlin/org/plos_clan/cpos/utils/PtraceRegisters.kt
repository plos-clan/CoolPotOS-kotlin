package org.plos_clan.cpos.utils

import kotlinx.cinterop.*

@ExperimentalForeignApi
class PtraceRegisters(private val registers: CPointer<ULongVar>) {
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
