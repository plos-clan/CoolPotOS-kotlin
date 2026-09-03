@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.TimerFd
import org.plos_clan.cpos.fs.vfs.TimerFdClock
import org.plos_clan.cpos.fs.vfs.TimerFdSetting
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.IntervalTimerSpec
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object TimerFdSyscalls {
    private const val TFD_TIMER_ABSTIME = 0x1uL
    private const val TFD_NONBLOCK = 0x0000_0800uL
    private const val TFD_CLOEXEC = 0x0008_0000uL
    private const val CREATE_FLAGS = 0x0008_0800uL
    private const val SETTIME_FLAGS = 0x3uL

    fun create(regs: PtraceRegisters, process: Process): Long {
        val clock = TimerFdClock.from(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EINVAL)
        val flags = regs[PtraceRegisters.IDX_RSI]
        if (flags and CREATE_FLAGS.inv() != 0uL) return errno(Errno.EINVAL)

        val context = process.context ?: return errno(Errno.ENOENT)
        val file = when (val result = FileSystemManager.vfs.createTimerFd(
            process.vfsOperationContext,
            context,
            clock,
            nonBlocking = flags and TFD_NONBLOCK != 0uL,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val descriptorFlags = if (flags and TFD_CLOEXEC != 0uL) {
            FileDescriptorFlags.FD_CLOEXEC
        } else {
            0uL
        }
        return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
            file.release()
            errno(Errno.EMFILE)
        }
    }

    fun setTime(regs: PtraceRegisters, process: Process): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val flags = regs[PtraceRegisters.IDX_RSI]
        if (flags and SETTIME_FLAGS.inv() != 0uL) return errno(Errno.EINVAL)

        val bytes = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDX])
            .copyFromUser(IntervalTimerSpec.NATIVE_SIZE) ?: return errno(Errno.EFAULT)
        val requested = IntervalTimerSpec.fromNativeBytes(bytes) ?: return errno(Errno.EFAULT)
        if (!requested.interval.isValidDuration || !requested.value.isValidDuration) {
            return errno(Errno.EINVAL)
        }
        val setting = TimerFdSetting(
            requested.interval.durationNanos,
            requested.value.durationNanos,
        )

        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            val timer = file.backend as? TimerFd ?: return errno(Errno.EINVAL)
            val absolute = flags and TFD_TIMER_ABSTIME != 0uL
            val previous = timer.setTime(setting, absolute)
            val previousAddress = regs[PtraceRegisters.IDX_R10]
            if (previousAddress == 0uL) return 0L

            val previousBytes = IntervalTimerSpec(
                previous.intervalNanos,
                previous.valueNanos,
            ).toNativeBytes()
            if (UserMemory(process.addressSpace, previousAddress).copyToUser(previousBytes)) 0L
            else errno(Errno.EFAULT)
        } finally {
            file.release()
        }
    }

    fun getTime(regs: PtraceRegisters, process: Process): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            val timer = file.backend as? TimerFd ?: return errno(Errno.EINVAL)
            val setting = timer.getTime()
            val bytes = IntervalTimerSpec(
                setting.intervalNanos,
                setting.valueNanos,
            ).toNativeBytes()
            if (UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI]).copyToUser(bytes)) {
                0L
            } else {
                errno(Errno.EFAULT)
            }
        } finally {
            file.release()
        }
    }
}
