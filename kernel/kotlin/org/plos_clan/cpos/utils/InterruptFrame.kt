package org.plos_clan.cpos.utils

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get

@ExperimentalForeignApi
class InterruptFrame(private val registers: CPointer<ULongVar>) {
    val rip: ULong get() = registers[0]
    val cs: ULong get() = registers[1]
    val rflags: ULong get() = registers[2]
    val rsp: ULong get() = registers[3]
    val ss: ULong get() = registers[4]
}
