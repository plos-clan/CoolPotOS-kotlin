@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun ioctl(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        file.ioctl(
            command = regs[PtraceRegisters.IDX_RSI].toInt(),
            args = UserMemory(
                process.addressSpace,
                regs[PtraceRegisters.IDX_RDX],
            ),
        )
    } finally {
        file.release()
    }
}

internal fun chdir(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    if (pathname.isEmpty()) return errno(Errno.ENOENT)
    val context = process.context ?: return errno(Errno.ENOENT)
    return when (val result = FileSystemManager.vfs.chdir(
        context,
        VfsPathname.fromBytes(pathname),
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun fchdir(regs: PtraceRegisters, process: Process): Long {
    val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
    return try {
        val context = process.context ?: return errno(Errno.ENOENT)
        when (val result = FileSystemManager.vfs.chdir(context, file.path)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun getCwd(regs: PtraceRegisters, process: Process): Long {
    val userAddress = regs[PtraceRegisters.IDX_RDI]
    val length = regs[PtraceRegisters.IDX_RSI]
    if (userAddress == 0UL) return errno(Errno.EFAULT)

    val context = process.context ?: return errno(Errno.ENOENT)

    val path = when (val result = FileSystemManager.vfs.absolutePath(
        context,
        context.workingDirectory,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }

    val result = ByteArray(path.size + 1)
    path.copyInto(result)

    if (length < result.size.toULong()) {
        return errno(Errno.ERANGE)
    }

    if (!UserMemory(process.addressSpace, userAddress).copyToUser(result)) {
        return errno(Errno.EFAULT)
    }

    return result.size.toLong()
}
