@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object EventFdSyscalls {
    fun eventfd2(regs: PtraceRegisters, process: Process): Long {
        val flags = EventFdFlags.from(regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EINVAL)
        val context = process.context ?: return errno(Errno.ENOENT)
        val file = when (val result = FileSystemManager.vfs.createEventFd(
            process.vfsOperationContext,
            context,
            regs[PtraceRegisters.IDX_RDI].toUInt(),
            flags.semaphore,
            flags.nonBlocking,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val descriptorFlags = if (flags.closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
        return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
            file.release()
            errno(Errno.EMFILE)
        }
    }

    private value class EventFdFlags private constructor(private val bits: UInt) {
        val semaphore: Boolean
            get() = bits and EFD_SEMAPHORE != 0u

        val closeOnExec: Boolean
            get() = bits and EFD_CLOEXEC != 0u

        val nonBlocking: Boolean
            get() = bits and EFD_NONBLOCK != 0u

        companion object {
            private const val EFD_SEMAPHORE = 0x0000_0001u
            private const val EFD_NONBLOCK = 0x0000_0800u
            private const val EFD_CLOEXEC = 0x0008_0000u
            private const val SUPPORTED = 0x0008_0801u

            fun from(raw: ULong): EventFdFlags? = raw.takeIf {
                it <= UInt.MAX_VALUE.toULong()
            }?.toUInt()?.takeIf {
                it and SUPPORTED.inv() == 0u
            }?.let(::EventFdFlags)
        }
    }
}
