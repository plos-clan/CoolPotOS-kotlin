@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.Inotify
import org.plos_clan.cpos.fs.vfs.InotifyWatchRequest
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object InotifySyscalls {
    private const val IN_NONBLOCK = 0x0000_0800u
    private const val IN_CLOEXEC = 0x0008_0000u
    private const val SUPPORTED_INIT_FLAGS = 0x0008_0800u

    fun init(regs: PtraceRegisters, process: Process): Long = create(process, 0u)

    fun init1(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDI].toUInt()
        if (flags and SUPPORTED_INIT_FLAGS.inv() != 0u) return errno(Errno.EINVAL)
        return create(process, flags)
    }

    fun addWatch(regs: PtraceRegisters, process: Process): Long {
        val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EFAULT)
        val request = InotifyWatchRequest.from(regs[PtraceRegisters.IDX_RDX].toUInt())
            ?: return errno(Errno.EINVAL)
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        try {
            val inotify = file.backend as? Inotify ?: return errno(Errno.EINVAL)
            val context = process.context ?: return errno(Errno.ENOENT)
            val path = when (val result = FileSystemManager.vfs.resolve(
                process.vfsOperationContext,
                context,
                VfsPathname.fromBytes(pathname),
                request.followFinalSymlink,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return errno(result.error.errno)
            }
            val inode = path.inode ?: return errno(Errno.ENOENT)
            if (request.onlyDirectory && inode.type != InodeType.DIRECTORY) {
                return errno(Errno.ENOTDIR)
            }
            when (val access = FileSystemManager.vfs.checkAccess(
                process.vfsOperationContext,
                inode,
                AccessPermissions.READ,
            )) {
                is VfsResult.Ok -> Unit
                is VfsResult.Err -> return errno(access.error.errno)
            }
            return when (val result = inotify.addWatch(inode, request)) {
                is VfsResult.Ok -> result.value.toLong()
                is VfsResult.Err -> errno(result.error.errno)
            }
        } finally {
            file.release()
        }
    }

    fun removeWatch(regs: PtraceRegisters, process: Process): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            val inotify = file.backend as? Inotify ?: return errno(Errno.EINVAL)
            when (val result = inotify.removeWatch(regs[PtraceRegisters.IDX_RSI].toInt())) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        } finally {
            file.release()
        }
    }

    private fun create(process: Process, flags: UInt): Long {
        val context = process.context ?: return errno(Errno.ENOENT)
        val file = when (val result = FileSystemManager.vfs.createInotify(
            process.vfsOperationContext,
            context,
            nonBlocking = flags and IN_NONBLOCK != 0u,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val descriptorFlags = if (flags and IN_CLOEXEC != 0u) {
            FileDescriptorFlags.FD_CLOEXEC
        } else {
            0uL
        }
        return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
            file.release()
            errno(Errno.EMFILE)
        }
    }
}
