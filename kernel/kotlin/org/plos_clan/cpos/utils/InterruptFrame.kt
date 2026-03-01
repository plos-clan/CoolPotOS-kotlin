package org.plos_clan.cpos.utils

import kotlinx.cinterop.*

@ExperimentalForeignApi
class InterruptFrame(var regs: CPointer<ULongVar>) {
    val rip: ULong get() = regs[0]
    val cs: ULong get() = regs[1]
    val rflags: ULong get() = regs[2]
    val rsp: ULong get() = regs[3]
    val ss: ULong get() = regs[4]
}
