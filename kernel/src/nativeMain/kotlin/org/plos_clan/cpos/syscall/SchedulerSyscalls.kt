@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.CpuAffinity
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object SchedulerSyscalls {
    fun yield(regs: PtraceRegisters, process: Process): Long {
        Scheduler.yieldCurrent()
        return 0L
    }

    fun getAffinity(regs: PtraceRegisters, process: Process): Long = affinity(regs, process, false)
    fun setAffinity(regs: PtraceRegisters, process: Process): Long = affinity(regs, process, true)

    private fun affinity(regs: PtraceRegisters, process: Process, set: Boolean): Long {
        val pid = regs[PtraceRegisters.IDX_RDI].toInt()
        if (pid < 0) return Syscall.errno(Errno.ESRCH)
        val caller = ProcessManager.currentThread() ?: return Syscall.errno(Errno.ESRCH)
        val target = if (pid == 0) caller else ProcessManager.findThread(pid)
            ?: return Syscall.errno(Errno.ESRCH)
        val size = regs[PtraceRegisters.IDX_RSI]
        val address = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDX])
        val online = CpuAffinity.online()
        if (!set) {
            if (size < online.size.toULong() || size % Long.SIZE_BYTES.toULong() != 0uL) {
                return Syscall.errno(Errno.EINVAL)
            }
            val mask = target.nativeTask.affinity() ?: return Syscall.errno(Errno.ESRCH)
            if (!address.copyToUser(mask)) return Syscall.errno(Errno.EFAULT)
            return mask.size.toLong()
        }
        val owner = target.process.credentials.userIds
        val uid = process.credentials.userIds.effective
        val ownsTarget = uid == owner.real || uid == owner.effective
        if (!ownsTarget && !caller.capabilities.hasEffective(CapEnum.SYS_NICE)) {
            return Syscall.errno(Errno.EPERM)
        }
        val inputSize = minOf(size, online.size.toULong()).toInt()
        val input = address.copyFromUser(inputSize)
            ?: return Syscall.errno(Errno.EFAULT)
        val mask = ByteArray(online.size) { index ->
            (online[index].toInt() and (input.getOrNull(index)?.toInt() ?: 0)).toByte()
        }
        if (mask.all { it == 0.toByte() }) return Syscall.errno(Errno.EINVAL)
        if (!target.nativeTask.setAffinity(mask)) return Syscall.errno(Errno.ESRCH)
        return 0L
    }
}
