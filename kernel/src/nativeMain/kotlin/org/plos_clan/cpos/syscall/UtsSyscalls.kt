@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.UtsNamespace
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object UtsSyscalls {
    fun uname(regs: PtraceRegisters, process: Process): Long {
        val output = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
        return if (UtsNamespace.initial.copyTo(output)) 0L else errno(Errno.EFAULT)
    }

    fun setHostname(regs: PtraceRegisters, process: Process): Long =
        setName(regs, process, UtsNamespace.MutableField.NODE_NAME)

    fun setDomainName(regs: PtraceRegisters, process: Process): Long =
        setName(regs, process, UtsNamespace.MutableField.DOMAIN_NAME)

    private fun setName(
        regs: PtraceRegisters,
        process: Process,
        field: UtsNamespace.MutableField,
    ): Long {
        val permitted = ProcessManager.currentThread()?.capabilities
            ?.hasEffective(CapEnum.SYS_ADMIN) == true
        if (!permitted) return errno(Errno.EPERM)

        val length = regs[PtraceRegisters.IDX_RSI]
        if (length > UtsNamespace.MAX_NAME_LENGTH.toULong()) {
            return errno(Errno.EINVAL)
        }
        val name = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
            .copyFromUser(length.toInt())
            ?: return errno(Errno.EFAULT)

        UtsNamespace.initial.setName(field, name)
        return 0L
    }
}
