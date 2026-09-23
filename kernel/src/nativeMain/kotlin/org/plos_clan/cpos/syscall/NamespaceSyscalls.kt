@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.network.NetworkStack
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object NamespaceSyscalls {
    const val CLONE_NEWNET = 0x4000_0000uL

    private enum class Resource(val flag: ULong) {
        TIME(0x0000_0080uL),
        MEMORY(0x0000_0100uL),
        FILE_SYSTEM(0x0000_0200uL),
        DESCRIPTORS(0x0000_0400uL),
        SIGNAL_HANDLERS(0x0000_0800uL),
        THREAD_GROUP(0x0001_0000uL),
        MOUNTS(0x0002_0000uL),
        SEMAPHORES(0x0004_0000uL),
        CGROUP(0x0200_0000uL),
        UTS(0x0400_0000uL),
        IPC(0x0800_0000uL),
        USER(0x1000_0000uL),
        PID(0x2000_0000uL),
        NETWORK(CLONE_NEWNET),
    }

    private val validFlags = Resource.entries.fold(0uL) { flags, resource ->
        flags or resource.flag
    }

    fun unshare(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDI]
        if (flags == 0uL) return 0L
        if (flags and validFlags.inv() != 0uL) return errno(Errno.EINVAL)
        if (flags and CLONE_NEWNET.inv() != 0uL) return errno(Errno.EOPNOTSUPP)
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        if (!thread.capabilities.hasEffective(CapEnum.SYS_ADMIN)) return errno(Errno.EPERM)
        thread.network = NetworkStack()
        return 0L
    }
}
