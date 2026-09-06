@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object PidFdSyscalls {
    fun open(regs: PtraceRegisters, process: Process): Long {
        val flags = Flags.from(regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EINVAL)
        val pid = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
        if (pid <= 0) return errno(Errno.EINVAL)
        val thread = ProcessManager.findThread(pid)
            ?.takeUnless { it.process.isKernelProcess }
            ?: return errno(Errno.ESRCH)
        if (flags.scope == PidHandle.Scope.PROCESS && thread.id != thread.process.id) {
            return errno(Errno.ENOENT)
        }

        val target = PidHandle(thread, flags.scope)
        if (target.state == PidHandle.State.DEAD) return errno(Errno.ESRCH)
        return when (val result = prepare(process, target, flags.statusFlags)) {
            is VfsResult.Ok -> result.value.use { it.install().toLong() }
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun prepare(
        process: Process,
        target: PidHandle,
        statusFlags: Int = 0,
    ): VfsResult<FileDescriptorTable.Reservation> {
        val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val threadFlag = if (target.scope == PidHandle.Scope.THREAD) OpenFlags.O_EXCL else 0
        val file = when (val result = FileSystemManager.vfs.createPidFd(
            process.vfsOperationContext,
            context,
            target,
            statusFlags or threadFlag,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val reservation = process.fdTable.reserve(
            file,
            FileDescriptorFlags.FD_CLOEXEC,
            process.resourceLimits.get(ProcessResource.OPEN_FILES).soft,
        ) ?: run {
            file.release()
            return VfsResult.Err(VfsError.fromErrno(Errno.EMFILE))
        }
        return VfsResult.Ok(reservation)
    }

    private value class Flags private constructor(val statusFlags: Int) {
        val scope: PidHandle.Scope
            get() = if (statusFlags and OpenFlags.O_EXCL != 0) {
                PidHandle.Scope.THREAD
            } else {
                PidHandle.Scope.PROCESS
            }

        companion object {
            private const val SUPPORTED = OpenFlags.O_NONBLOCK or OpenFlags.O_EXCL

            fun from(raw: ULong): Flags? = raw.toUInt().toInt()
                .takeIf { it and SUPPORTED.inv() == 0 }
                ?.let(::Flags)
        }
    }
}
