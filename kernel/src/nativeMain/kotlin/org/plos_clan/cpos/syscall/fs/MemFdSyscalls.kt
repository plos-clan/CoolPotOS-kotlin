@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.tmpfs.MemFd
import org.plos_clan.cpos.fs.tmpfs.MemFdFlags
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters

internal object MemFdSyscalls {
    fun create(regs: PtraceRegisters, process: Process): Long {
        val flags = when (val result = MemFdFlags.from(regs[PtraceRegisters.IDX_RSI].toUInt())) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val address = regs[PtraceRegisters.IDX_RDI]
        val memory = UserMemory(process.addressSpace, address)
        val name = ByteArray(MemFd.NAME_MAX + 1)
        var offset = 0
        while (offset < name.size) {
            val count = minOf(name.size - offset, (PAGE_SIZE_BYTES - (address + offset.toULong()) % PAGE_SIZE_BYTES).toInt())
            if (memory.copyTo(offset, name, offset, count) != count) return errno(Errno.EFAULT)
            for (end in offset until offset + count) {
                if (name[end] != 0.toByte()) continue
                val file = when (val result = MemFd.create(process.vfsOperationContext, name.copyOf(end), flags)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return errno(result.error.errno)
                }
                val descriptorFlags = if (flags.closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
                return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
                    file.release()
                    errno(Errno.EMFILE)
                }
            }
            offset += count
        }
        return errno(Errno.EINVAL)
    }
}
