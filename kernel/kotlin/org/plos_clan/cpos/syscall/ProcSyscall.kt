@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.syscall.Syscall.copyWordToUser
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

private const val ARCH_SET_GS = 0x1001uL
private const val ARCH_SET_FS = 0x1002uL
private const val ARCH_GET_FS = 0x1003uL
private const val ARCH_GET_GS = 0x1004uL
private const val MSR_KERNEL_GS_BASE = 0xC0000102U

fun sysArchPrctl(regs: PtraceRegisters, process: Process): Long {
    val command = regs[PtraceRegisters.IDX_RDI]
    val argument = regs[PtraceRegisters.IDX_RSI]
    return when (command) {
        ARCH_SET_FS -> {
            if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                errno(Errno.EPERM)
            } else {
                regs[PtraceRegisters.IDX_FS_BASE] = argument
                0L
            }
        }

        ARCH_SET_GS -> {
            if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                errno(Errno.EPERM)
            } else {
                bridge.wrmsr(MSR_KERNEL_GS_BASE, argument)
                0L
            }
        }

        ARCH_GET_FS -> copyWordToUser(
            process,
            argument,
            regs[PtraceRegisters.IDX_FS_BASE],
        )

        ARCH_GET_GS -> copyWordToUser(
            process,
            argument,
            bridge.rdmsr(MSR_KERNEL_GS_BASE),
        )

        else -> errno(Errno.EINVAL)
    }
}

fun sysGetPID(regs: PtraceRegisters, process: Process): Long {
    return process.id.toLong()
}

fun sysGetUID(regs: PtraceRegisters, process: Process): Long {
    return process.uid.toLong()
}

fun sysGetGID(regs: PtraceRegisters, process: Process): Long {
    return process.rgid.toLong()
}

fun sysGetSID(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toInt()
    val process =
        if (pid == 0) process else ProcessManager.findProcess(pid) ?: return errno(Errno.ESRCH)
    return process.sid.toLong()
}

fun sysGetTID(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread()!!
    return thread.id.toLong()
}

/**
 * Record the address that the thread-exit path must clear. Linux does not
 * dereference this pointer while handling set_tid_address; the eventual exit
 * path validates it when it writes zero and wakes a possible futex waiter.
 */
fun sysSetTidAddress(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    thread.clearChildTid = regs[PtraceRegisters.IDX_RDI]
    return thread.id.toLong()
}
