package org.plos_clan.cpos.utils

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

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
        const val EXTENDED_STATE_SIZE = 832
        const val NO_SYSCALL = ULong.MAX_VALUE
        const val SIGNAL_RETURN = 0xffff_ffff_ffff_fffeuL
        private const val SIGNAL_FRAME_INSTALLED = ULong.MAX_VALUE
        private const val EXTENDED_STATE_OFFSET = 256
        private val VALID_REGISTER_INDEXES = 0 until REGISTER_COUNT
    }

    operator fun get(index: Int): ULong =
        if (index in VALID_REGISTER_INDEXES) {
            registers[index]
        } else {
            0uL
        }

    operator fun set(index: Int, value: ULong) {
        if (index in VALID_REGISTER_INDEXES) {
            registers[index] = value
        }
    }

    val signalFrameInstalled: Boolean
        get() = registers[IDX_ERRCODE] == SIGNAL_FRAME_INSTALLED

    fun markSignalFrameInstalled() {
        registers[IDX_ERRCODE] = SIGNAL_FRAME_INSTALLED
    }

    fun copyInto(destination: ULongArray) =
        repeat(minOf(destination.size, REGISTER_COUNT)) { index ->
            destination[index] = registers[index]
        }

    fun restoreFrom(source: ULongArray) =
        repeat(minOf(source.size, REGISTER_COUNT)) { index ->
            registers[index] = source[index]
        }

    fun copyExtendedStateTo(destination: ByteArray, offset: Int) {
        require(offset >= 0 && offset <= destination.size - EXTENDED_STATE_SIZE)
        destination.usePinned { bytes ->
            memcpy(
                bytes.addressOf(offset),
                requireNotNull(registers.reinterpret<UByteVar>() + EXTENDED_STATE_OFFSET),
                EXTENDED_STATE_SIZE.toULong(),
            )
        }
    }

    fun restoreExtendedState(source: ByteArray, offset: Int = 0): Boolean {
        if (offset < 0 || offset > source.size - EXTENDED_STATE_SIZE) return false
        source.usePinned { bytes ->
            memcpy(
                requireNotNull(registers.reinterpret<UByteVar>() + EXTENDED_STATE_OFFSET),
                bytes.addressOf(offset),
                EXTENDED_STATE_SIZE.toULong(),
            )
        }
        return true
    }
}
