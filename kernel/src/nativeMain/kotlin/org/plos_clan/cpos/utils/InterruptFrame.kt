package org.plos_clan.cpos.utils

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set

@ExperimentalForeignApi
class InterruptFrame(private val registers: CPointer<ULongVar>) {
    var rip: ULong
        get() = registers[0]
        set(value) {
            registers[0] = value
        }

    val cs: ULong get() = registers[1]

    var rflags: ULong
        get() = registers[2]
        set(value) {
            registers[2] = value
        }

    var rsp: ULong
        get() = registers[3]
        set(value) {
            registers[3] = value
        }

    val ss: ULong get() = registers[4]

    val cameFromUser: Boolean
        get() = cs and 0x3uL == 0x3uL
}
