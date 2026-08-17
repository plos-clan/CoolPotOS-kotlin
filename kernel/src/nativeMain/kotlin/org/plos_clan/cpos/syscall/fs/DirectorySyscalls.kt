@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.FsConstants.DIRENT64_MIN_SIZE
import org.plos_clan.cpos.syscall.FsConstants.IO_CHUNK_SIZE
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun getdents64(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val requested = regs[PtraceRegisters.IDX_RDX]
    if (requested == 0uL) return 0L

    val capacity = minOf(requested, IO_CHUNK_SIZE.toULong()).toInt()
    if (capacity < DIRENT64_MIN_SIZE) return errno(Errno.EINVAL)
    val user = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
    if (!user.isWritable(capacity)) return errno(Errno.EFAULT)

    val output = ByteArray(capacity)
    var written = 0
    var encountered = false
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val result = file.iterate { entry, nextOffset ->
            encountered = true
            val record = LinuxDirent64(entry, nextOffset)
            if (record.recordSize > capacity - written) {
                false
            } else {
                record.toNativeBytes().copyInto(output, written)
                written += record.recordSize
                true
            }
        }
        if (result is VfsResult.Err && written == 0) return errno(result.error.errno)
    } finally {
        file.release()
    }

    if (written == 0 && encountered) return errno(Errno.EINVAL)
    if (written != 0 && !user.copyToUser(output, size = written)) {
        return partialOrError(0uL, Errno.EFAULT)
    }
    return written.toLong()
}
