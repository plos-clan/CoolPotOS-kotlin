@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.addressspace.MemoryLock
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object MemoryLockSyscalls {
    fun limit(process: Process): ULong {
        val capabilities = ProcessManager.currentThread()?.capabilities
        if (capabilities?.hasEffective(CapEnum.IPC_LOCK) == true) return ULong.MAX_VALUE
        return process.resourceLimits.get(ProcessResource.LOCKED_MEMORY).soft
    }

    fun lock(regs: PtraceRegisters, process: Process): Long = range(regs, process, MemoryLock.EAGER)

    fun lockOnFault(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDX].toUInt()
        if (flags > 1u) return -Errno.EINVAL.toLong()
        val mode = if (flags == 0u) MemoryLock.EAGER else MemoryLock.ON_FAULT
        return range(regs, process, mode)
    }

    fun unlock(regs: PtraceRegisters, process: Process): Long =
        range(regs, process, MemoryLock.NONE)

    private fun range(regs: PtraceRegisters, process: Process, mode: MemoryLock): Long {
        val maximum = limit(process)
        if (mode != MemoryLock.NONE && maximum == 0uL) return -Errno.EPERM.toLong()
        val address = regs[PtraceRegisters.IDX_RDI]
        val length = regs[PtraceRegisters.IDX_RSI]
        return process.addressSpace.lockMemory(address, length, mode, maximum).toLong()
    }

    fun lockAll(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDI].toUInt()
        val current = flags and 1u != 0u
        val future = flags and 2u != 0u
        val unknownFlags = flags and 7u.inv() != 0u
        if (unknownFlags || !current && !future) return -Errno.EINVAL.toLong()
        val maximum = limit(process)
        if (maximum == 0uL) return -Errno.EPERM.toLong()
        val mode = if (flags and 4u == 0u) MemoryLock.EAGER else MemoryLock.ON_FAULT
        return process.addressSpace.lockAllMemory(mode, current, future, maximum).toLong()
    }

    fun unlockAll(regs: PtraceRegisters, process: Process): Long =
        process.addressSpace.lockAllMemory(
            MemoryLock.NONE, current = true, future = false, maximum = ULong.MAX_VALUE,
        ).toLong()
}
