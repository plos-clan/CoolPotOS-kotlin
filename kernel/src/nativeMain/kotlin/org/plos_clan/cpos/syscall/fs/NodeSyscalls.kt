@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.drivers.DeviceNumber
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.RemoveMode
import org.plos_clan.cpos.fs.vfs.RenameMode
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_EMPTY_PATH
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_REMOVEDIR
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_SYMLINK_FOLLOW
import org.plos_clan.cpos.syscall.fs.FsConstants.RENAME_EXCHANGE
import org.plos_clan.cpos.syscall.fs.FsConstants.RENAME_NOREPLACE
import org.plos_clan.cpos.syscall.fs.FsConstants.RENAME_WHITEOUT
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IALLUGO
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFBLK
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFCHR
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFIFO
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFMT
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFREG
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IFSOCK
import org.plos_clan.cpos.syscall.fs.FsPathResolver.atPath
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun mkdir(regs: PtraceRegisters, process: Process): Long = createNodeAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI],
    NodeKind.Directory,
)

internal fun mkdirAt(regs: PtraceRegisters, process: Process): Long = createNodeAt(
    process,
    regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX],
    NodeKind.Directory,
)

internal fun mknod(regs: PtraceRegisters, process: Process): Long = mknodAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX],
)

internal fun mknodAt(regs: PtraceRegisters, process: Process): Long = mknodAt(
    process,
    regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX],
    regs[PtraceRegisters.IDX_R10],
)

private fun mknodAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    rawMode: ULong,
    rawDevice: ULong,
): Long {
    val kind = when (val fileType = rawMode.toUInt() and S_IFMT) {
        0u, S_IFREG -> NodeKind.Regular
        S_IFIFO -> NodeKind.Fifo
        S_IFSOCK -> NodeKind.Socket
        S_IFCHR, S_IFBLK -> {
            if (process.euid != 0) return errno(Errno.EPERM)
            val number = DeviceNumber.fromEncoded(rawDevice) ?: return errno(Errno.EINVAL)
            NodeKind.Device(
                if (fileType == S_IFCHR) InodeType.CHARACTER_DEVICE
                else InodeType.BLOCK_DEVICE,
                number.value,
            )
        }
        else -> return errno(Errno.EINVAL)
    }
    return createNodeAt(process, dirFd, pathnameAddress, rawMode, kind)
}

private fun createNodeAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    rawMode: ULong,
    kind: NodeKind,
): Long {
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val target = when (val result = atPath(
        process,
        dirFd,
        VfsPathname.fromBytes(pathname),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val mode = rawMode.toUInt() and S_IALLUGO and caller.fileCreationMask.inv()
    val node = NodeCreation(
        kind,
        FileMode(mode),
        caller.uid,
        caller.gid,
    )
    return when (val result = FileSystemManager.vfs.createNode(
        caller,
        target.context,
        target.directory,
        target.pathname,
        node,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun unlink(regs: PtraceRegisters, process: Process): Long = removeAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    RemoveMode.FILE,
)

internal fun rmdir(regs: PtraceRegisters, process: Process): Long = removeAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    RemoveMode.DIRECTORY,
)

internal fun unlinkAt(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_RDX]
    if (flags != 0uL && flags != AT_REMOVEDIR.toULong()) return errno(Errno.EINVAL)
    return removeAt(
        process,
        regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
        regs[PtraceRegisters.IDX_RSI],
        if (flags == 0uL) RemoveMode.FILE else RemoveMode.DIRECTORY,
    )
}

private fun removeAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    mode: RemoveMode,
): Long {
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val target = when (val result = atPath(
        process,
        dirFd,
        VfsPathname.fromBytes(pathname),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return when (val result = FileSystemManager.vfs.remove(
        caller,
        target.context,
        target.directory,
        target.pathname,
        mode,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun symlink(regs: PtraceRegisters, process: Process): Long = symlinkAt(
    process,
    regs[PtraceRegisters.IDX_RDI],
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RSI],
)

internal fun symlinkAt(regs: PtraceRegisters, process: Process): Long = symlinkAt(
    process,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI].toUInt().toInt(),
    regs[PtraceRegisters.IDX_RDX],
)

private fun symlinkAt(
    process: Process,
    targetAddress: ULong,
    dirFd: Int,
    pathnameAddress: ULong,
): Long {
    val target = copyPath(process, targetAddress) ?: return errno(Errno.EFAULT)
    if (target.isEmpty()) return errno(Errno.ENOENT)
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val link = when (val result = atPath(
        process,
        dirFd,
        VfsPathname.fromBytes(pathname),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val node = NodeCreation(
        NodeKind.SymbolicLink(VfsPathname.fromBytes(target)),
        FileMode(0x1ffu),
        caller.uid,
        caller.gid,
    )
    return when (val result = FileSystemManager.vfs.createNode(
        caller,
        link.context,
        link.directory,
        link.pathname,
        node,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun rename(regs: PtraceRegisters, process: Process): Long = renameAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RSI],
    RenameMode.REPLACE,
)

internal fun renameAt(regs: PtraceRegisters, process: Process): Long = renameAt(
    process,
    regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX].toUInt().toInt(),
    regs[PtraceRegisters.IDX_R10],
    RenameMode.REPLACE,
)

internal fun renameAt2(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R8]
    val supported = (RENAME_NOREPLACE or RENAME_EXCHANGE or RENAME_WHITEOUT).toULong()
    if (flags and supported.inv() != 0uL || flags and RENAME_WHITEOUT.toULong() != 0uL ||
        flags and RENAME_NOREPLACE.toULong() != 0uL &&
        flags and RENAME_EXCHANGE.toULong() != 0uL
    ) {
        return errno(Errno.EINVAL)
    }
    val mode = when (flags.toInt()) {
        0 -> RenameMode.REPLACE
        RENAME_NOREPLACE -> RenameMode.NO_REPLACE
        RENAME_EXCHANGE -> RenameMode.EXCHANGE
        else -> return errno(Errno.EINVAL)
    }
    return renameAt(
        process,
        regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
        regs[PtraceRegisters.IDX_RSI],
        regs[PtraceRegisters.IDX_RDX].toUInt().toInt(),
        regs[PtraceRegisters.IDX_R10],
        mode,
    )
}

private fun renameAt(
    process: Process,
    sourceDirFd: Int,
    sourceAddress: ULong,
    targetDirFd: Int,
    targetAddress: ULong,
    mode: RenameMode,
): Long {
    val sourceBytes = copyPath(process, sourceAddress) ?: return errno(Errno.EFAULT)
    val targetBytes = copyPath(process, targetAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val source = when (val result = atPath(
        process,
        sourceDirFd,
        VfsPathname.fromBytes(sourceBytes),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val target = when (val result = atPath(
        process,
        targetDirFd,
        VfsPathname.fromBytes(targetBytes),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return when (val result = FileSystemManager.vfs.rename(
        caller,
        source.context,
        source.directory,
        source.pathname,
        target.directory,
        target.pathname,
        mode,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun link(regs: PtraceRegisters, process: Process): Long = linkAt(
    process,
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RDI],
    AT_FDCWD,
    regs[PtraceRegisters.IDX_RSI],
    0uL,
)

internal fun linkAt(regs: PtraceRegisters, process: Process): Long = linkAt(
    process,
    regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX].toUInt().toInt(),
    regs[PtraceRegisters.IDX_R10],
    regs[PtraceRegisters.IDX_R8],
)

private fun linkAt(
    process: Process,
    sourceDirFd: Int,
    sourceAddress: ULong,
    targetDirFd: Int,
    targetAddress: ULong,
    flags: ULong,
): Long {
    val supported = (AT_SYMLINK_FOLLOW or AT_EMPTY_PATH).toULong()
    if (flags and supported.inv() != 0uL) return errno(Errno.EINVAL)
    val caller = process.vfsOperationContext
    val sourceBytes = copyPath(process, sourceAddress) ?: return errno(Errno.EFAULT)
    val source = if (sourceBytes.isEmpty() && flags and AT_EMPTY_PATH.toULong() != 0uL) {
        if (process.euid != 0) return errno(Errno.EPERM)
        val file = process.fdTable.acquire(sourceDirFd) ?: return errno(Errno.EBADF)
        try {
            val metadata = when (
                val result = file.inode.attributes(caller)
            ) {
                is VfsResult.Ok -> result.value.metadata
                is VfsResult.Err -> return errno(result.error.errno)
            }
            if (metadata.linkCount == 0u) return errno(Errno.ENOENT)
            file.path.mount to file.inode
        } finally {
            file.release()
        }
    } else {
        val path = when (val result = atPath(
            process,
            sourceDirFd,
            VfsPathname.fromBytes(sourceBytes),
            caller,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val resolved = when (val result = path.resolve(
            followFinalSymlink = flags and AT_SYMLINK_FOLLOW.toULong() != 0uL,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val inode = resolved.inode ?: return errno(Errno.ENOENT)
        resolved.mount to inode
    }
    val targetBytes = copyPath(process, targetAddress) ?: return errno(Errno.EFAULT)
    val target = when (val result = atPath(
        process,
        targetDirFd,
        VfsPathname.fromBytes(targetBytes),
        caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return when (val result = FileSystemManager.vfs.link(
        caller,
        target.context,
        source.first,
        source.second,
        target.directory,
        target.pathname,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}
