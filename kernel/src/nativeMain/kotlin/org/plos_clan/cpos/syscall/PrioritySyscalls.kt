@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.TaskScope
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object PrioritySyscalls {
    fun get(regs: PtraceRegisters, process: Process): Long = handle(regs, process, set = false)

    fun set(regs: PtraceRegisters, process: Process): Long = handle(regs, process, set = true)

    private fun handle(regs: PtraceRegisters, process: Process, set: Boolean): Long {
        val scope = TaskScope.entries.getOrNull(regs[PtraceRegisters.IDX_RDI].toInt())
            ?: return errno(Errno.EINVAL)
        val caller = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val who = regs[PtraceRegisters.IDX_RSI].toInt()
        val id = if (who != 0) who else when (scope) {
            TaskScope.PROCESS -> caller.id
            TaskScope.PROCESS_GROUP -> process.processGroupId
            TaskScope.USER -> process.credentials.userIds.real
        }
        val requested = regs[PtraceRegisters.IDX_RDX].toInt()
        val privileged = set && caller.capabilities.hasEffective(CapEnum.SYS_NICE)
        val uid = process.credentials.userIds.effective
        var result = errno(Errno.ESRCH)
        ProcessManager.forEachThread(scope, id) { target ->
            if (!set) {
                result = maxOf(result, target.priority.encoded.toLong())
                return@forEachThread
            }

            val owner = target.process.credentials.userIds
            val error = when {
                !privileged && uid != owner.real && uid != owner.effective -> Errno.EPERM
                !target.priority.set(
                    requested,
                    if (privileged) 0uL else target.process.resourceLimits.get(ProcessResource.NICE).soft,
                    privileged,
                ) -> Errno.EACCES
                else -> Errno.EOK
            }
            if (error != Errno.EOK || result == errno(Errno.ESRCH)) result = errno(error)
        }
        return result
    }
}
