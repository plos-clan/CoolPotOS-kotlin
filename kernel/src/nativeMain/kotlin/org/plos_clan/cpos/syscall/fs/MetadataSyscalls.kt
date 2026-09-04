@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemEvent
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeTimestampSet
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.TimeSpec
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_EACCESS
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_EMPTY_PATH
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_NO_AUTOMOUNT
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_STATX_FORCE_SYNC
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_STATX_SYNC_TYPE
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_SYMLINK_NOFOLLOW
import org.plos_clan.cpos.syscall.fs.FsConstants.STATX_RESERVED
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IALLUGO
import org.plos_clan.cpos.syscall.fs.FsConstants.S_ISGID
import org.plos_clan.cpos.syscall.fs.FsConstants.UTIME_NOW
import org.plos_clan.cpos.syscall.fs.FsConstants.UTIME_OMIT
import org.plos_clan.cpos.syscall.fs.FsPathResolver.resolveAt
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun umask(regs: PtraceRegisters, process: Process): Long {
    val previous = process.fileCreationMask
    process.fileCreationMask = regs[PtraceRegisters.IDX_RDI].toUInt() and 0x1ffu
    return previous.toLong()
}

internal fun chown(regs: PtraceRegisters, process: Process): Long = chownPath(
    process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    uid = regs[PtraceRegisters.IDX_RSI],
    gid = regs[PtraceRegisters.IDX_RDX],
    followFinalSymlink = true,
)

internal fun lchown(regs: PtraceRegisters, process: Process): Long = chownPath(
    process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    uid = regs[PtraceRegisters.IDX_RSI],
    gid = regs[PtraceRegisters.IDX_RDX],
    followFinalSymlink = false,
)

internal fun fchown(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    val caller = process.vfsOperationContext
    return try {
        changeOwner(
            caller,
            file.path,
            file.inode,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
        )
    } finally {
        file.release()
    }
}

internal fun fchownAt(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R8]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH).toULong()
    if (flags > UInt.MAX_VALUE.toULong() || flags and supportedFlags.inv() != 0uL) {
        return errno(Errno.EINVAL)
    }
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val caller = process.vfsOperationContext
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return changeOwner(
        caller,
        target,
        inode,
        regs[PtraceRegisters.IDX_RDX],
        regs[PtraceRegisters.IDX_R10],
    )
}

private fun chownPath(
    process: Process,
    pathnameAddress: ULong,
    uid: ULong,
    gid: ULong,
    followFinalSymlink: Boolean,
): Long {
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    if (pathname.isEmpty()) return errno(Errno.ENOENT)
    val caller = process.vfsOperationContext
    val target = when (val result = resolveAt(
        process,
        AT_FDCWD,
        VfsPathname.fromBytes(pathname),
        followFinalSymlink,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return changeOwner(caller, target, inode, uid, gid)
}

private fun changeOwner(
    caller: VfsOperationContext,
    path: VfsPath,
    inode: Inode,
    uid: ULong,
    gid: ULong,
): Long {
    if (!caller.privileged) return errno(Errno.EPERM)
    if (uid > UInt.MAX_VALUE.toULong() || gid > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val owner = uid.toUInt().takeUnless { it == UInt.MAX_VALUE }
    val group = gid.toUInt().takeUnless { it == UInt.MAX_VALUE }
    return when (val result = FileSystemManager.vfs.setOwner(
        caller,
        path,
        inode,
        owner,
        group,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun chmod(regs: PtraceRegisters, process: Process): Long = changeModeAt(
    process = process,
    dirFd = AT_FDCWD,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    rawMode = regs[PtraceRegisters.IDX_RSI],
    flags = 0uL,
)

internal fun fchmod(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    val caller = process.vfsOperationContext
    return try {
        if (file.access == AccessMode.PATH) errno(Errno.EBADF)
        else changeMode(caller, file.path, file.inode, regs[PtraceRegisters.IDX_RSI])
    } finally {
        file.release()
    }
}

internal fun fchmodAt(regs: PtraceRegisters, process: Process): Long = changeModeAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    rawMode = regs[PtraceRegisters.IDX_RDX],
    flags = 0uL,
)

internal fun fchmodAt2(regs: PtraceRegisters, process: Process): Long = changeModeAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    rawMode = regs[PtraceRegisters.IDX_RDX],
    flags = regs[PtraceRegisters.IDX_R10],
)

private fun changeModeAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    rawMode: ULong,
    flags: ULong,
): Long {
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH).toULong()
    if (flags > UInt.MAX_VALUE.toULong() || flags and supportedFlags.inv() != 0uL) {
        return errno(Errno.EINVAL)
    }
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    if (inode.type == InodeType.SYMLINK) return errno(Errno.EOPNOTSUPP)
    return changeMode(caller, target, inode, rawMode)
}

private fun changeMode(
    caller: VfsOperationContext,
    path: VfsPath,
    inode: Inode,
    rawMode: ULong,
): Long {
    val metadata = when (val result = inode.attributes(caller)) {
        is VfsResult.Ok -> result.value.metadata
        is VfsResult.Err -> return errno(result.error.errno)
    }
    if (!caller.privileged && caller.uid != metadata.uid) {
        return errno(Errno.EPERM)
    }

    var mode = rawMode.toUInt() and S_IALLUGO
    if (!caller.privileged && !caller.belongsToGroup(metadata.gid)) {
        mode = mode and S_ISGID.inv()
    }
    return when (val result = FileSystemManager.vfs.setMode(
        caller,
        path,
        inode,
        FileMode(mode),
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun utimensAt(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R10]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH).toULong()
    if (flags > UInt.MAX_VALUE.toULong() || flags and supportedFlags.inv() != 0uL) {
        return errno(Errno.EINVAL)
    }
    val pathnameAddress = regs[PtraceRegisters.IDX_RSI]
    if (pathnameAddress == 0uL && flags != 0uL) return errno(Errno.EINVAL)
    val timestamps = when (
        val result = copyTimestampSet(process, regs[PtraceRegisters.IDX_RDX])
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val caller = process.vfsOperationContext

    if (pathnameAddress == 0uL) {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            if (file.access == AccessMode.PATH && !timestamps.omitsBoth) errno(Errno.EBADF)
            else setTimestamps(caller, file.path, file.inode, timestamps)
        } finally {
            file.release()
        }
    }
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    if (pathname.isEmpty() && flags.toInt() and AT_EMPTY_PATH != 0) {
        if (dirFd == AT_FDCWD) {
            val path = process.context?.workingDirectory ?: return errno(Errno.ENOENT)
            val inode = path.inode ?: return errno(Errno.ENOENT)
            return setTimestamps(caller, path, inode, timestamps)
        }
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            setTimestamps(caller, file.path, file.inode, timestamps)
        } finally {
            file.release()
        }
    }
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return setTimestamps(caller, target, inode, timestamps)
}

private fun setTimestamps(
    caller: VfsOperationContext,
    path: VfsPath,
    inode: Inode,
    timestamps: InodeTimestampSet,
): Long {
    if (timestamps.omitsBoth) return 0L

    val metadata = when (val result = inode.attributes(caller)) {
        is VfsResult.Ok -> result.value.metadata
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val ownsFile = caller.privileged || caller.uid == metadata.uid
    if (!ownsFile) {
        if (!timestamps.setsBothToNow) return errno(Errno.EPERM)
        when (val access = FileSystemManager.vfs.checkAccess(
            caller,
            inode,
            AccessPermissions.WRITE,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return errno(access.error.errno)
        }
    }
    return when (val result = FileSystemManager.vfs.updateTimestamps(
        caller,
        path,
        inode,
        timestamps,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

private fun copyTimestampSet(
    process: Process,
    address: ULong,
): VfsResult<InodeTimestampSet> {
    if (address == 0uL) return VfsResult.Ok(InodeTimestampSet.NOW)
    val bytes = UserMemory(process.addressSpace, address).copyFromUser(TimeSpec.NATIVE_SIZE * 2)
        ?: return VfsResult.Err(VfsError.FAULT)
    val input = LittleEndianBuffer(bytes)
    val accessTime = when (val result = timestampValue(input, 0)) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return result
    }
    val modificationTime = when (val result = timestampValue(input, TimeSpec.NATIVE_SIZE)) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return result
    }
    return VfsResult.Ok(InodeTimestampSet(accessTime, modificationTime))
}

private fun timestampValue(
    input: LittleEndianBuffer,
    offset: Int,
): VfsResult<InodeTimestampSet.Value> {
    val value = when (val nanoseconds = input.readU64(offset + Long.SIZE_BYTES).toLong()) {
        UTIME_NOW -> InodeTimestampSet.Value.Now
        UTIME_OMIT -> InodeTimestampSet.Value.Omit
        in 0 until VfsTimestamp.NANOSECONDS_PER_SECOND.toLong() -> InodeTimestampSet.Value.Exact(
            VfsTimestamp(input.readU64(offset).toLong(), nanoseconds.toUInt()),
        )
        else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    }
    return VfsResult.Ok(value)
}

internal fun stat(regs: PtraceRegisters, process: Process): Long = statPath(
    process = process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    statAddress = regs[PtraceRegisters.IDX_RSI],
    followFinalSymlink = true,
)

internal fun lstat(regs: PtraceRegisters, process: Process): Long = statPath(
    process = process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    statAddress = regs[PtraceRegisters.IDX_RSI],
    followFinalSymlink = false,
)

internal fun statfs(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    if (pathname.isEmpty()) return errno(Errno.ENOENT)
    val caller = process.vfsOperationContext
    val path = when (val result = FileSystemManager.vfs.resolve(
        caller,
        process.getFSContext(),
        VfsPathname.fromBytes(pathname),
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return copyStatFs(process, caller, regs[PtraceRegisters.IDX_RSI], path)
}

internal fun fstatfs(regs: PtraceRegisters, process: Process): Long {
    val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
    val caller = process.vfsOperationContext
    return try {
        copyStatFs(
            process,
            caller,
            regs[PtraceRegisters.IDX_RSI],
            file.path,
            file.backend.fileSystemMagic ?: file.path.mount.superBlock.type.magic,
        )
    } finally {
        file.release()
    }
}

private fun copyStatFs(
    process: Process,
    caller: VfsOperationContext,
    address: ULong,
    path: VfsPath,
    fileSystemMagic: ULong = path.mount.superBlock.type.magic,
): Long {
    val statistics = when (
        val result = path.mount.superBlock.backend.statistics(caller)
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return if (UserMemory(process.addressSpace, address).copyToUser(
            LinuxStatFs(
                fileSystemMagic,
                path.mount.flags,
                statistics,
            ).toNativeBytes(),
        )
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun access(regs: PtraceRegisters, process: Process): Long {
    val mode = regs[PtraceRegisters.IDX_RSI]
    if (mode > 0x7uL) {
        return errno(Errno.EINVAL)
    }
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val context = process.context ?: return errno(Errno.ENOENT)
    val caller = process.accessContext(effective = false)
    val path = when (
        val result = FileSystemManager.vfs.resolve(
            caller,
            context = context,
            pathname = VfsPathname.fromBytes(pathname),
        )
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    val requested = AccessPermissions.fromBits(mode.toUInt()) ?: return errno(Errno.EINVAL)
    return when (val result = FileSystemManager.vfs.access(caller, inode, requested)) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun faccessAt(regs: PtraceRegisters, process: Process): Long = accessAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    mode = regs[PtraceRegisters.IDX_RDX],
    flags = 0uL,
)

internal fun faccessAt2(regs: PtraceRegisters, process: Process): Long = accessAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    mode = regs[PtraceRegisters.IDX_RDX],
    flags = regs[PtraceRegisters.IDX_R10],
)

private fun accessAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    mode: ULong,
    flags: ULong,
): Long {
    if (mode > 0x7uL || flags > UInt.MAX_VALUE.toULong()) return errno(Errno.EINVAL)
    val supportedFlags = (AT_EACCESS or AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH).toULong()
    if (flags and supportedFlags.inv() != 0uL) return errno(Errno.EINVAL)
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.accessContext(effective = flags.toInt() and AT_EACCESS != 0)
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    val requested = AccessPermissions.fromBits(mode.toUInt()) ?: return errno(Errno.EINVAL)
    return when (val result = FileSystemManager.vfs.access(caller, inode, requested)) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

private fun Process.accessContext(effective: Boolean) = vfsOperationContext.copy(
    uid = (if (effective) credentials.userIds.effective else credentials.userIds.real).toUInt(),
    gid = (if (effective) credentials.groupIds.effective else credentials.groupIds.real).toUInt(),
    privileged = (if (effective) credentials.userIds.effective else credentials.userIds.real) == 0,
)

internal fun newFstatAt(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R10]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_NO_AUTOMOUNT or AT_EMPTY_PATH).toULong()
    if (flags and supportedFlags.inv() != 0uL || flags > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }

    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val caller = process.vfsOperationContext
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return copyStat(process, caller, regs[PtraceRegisters.IDX_RDX], inode)
}

internal fun statx(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_RDX]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_NO_AUTOMOUNT or AT_EMPTY_PATH or
        AT_STATX_SYNC_TYPE).toULong()
    if (flags > UInt.MAX_VALUE.toULong() || flags and supportedFlags.inv() != 0uL ||
        flags.toInt() and AT_STATX_SYNC_TYPE == AT_STATX_SYNC_TYPE
    ) {
        return errno(Errno.EINVAL)
    }
    val mask = regs[PtraceRegisters.IDX_R10]
    if (mask > UInt.MAX_VALUE.toULong() || mask.toUInt() and STATX_RESERVED != 0u) {
        return errno(Errno.EINVAL)
    }

    val pathnameAddress = regs[PtraceRegisters.IDX_RSI]
    val pathname = if (pathnameAddress == 0uL && flags.toInt() and AT_EMPTY_PATH != 0) {
        ByteArray(0)
    } else {
        copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    }
    val caller = process.vfsOperationContext
    val target = when (val result = resolveAt(
        process = process,
        dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    val attributes = when (val result = inode.attributes(
        caller,
        forceRefresh = flags.toInt() and AT_STATX_FORCE_SYNC != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val status = LinuxStatx(
        LinuxFileStatus.snapshot(inode, attributes),
        isMountRoot = target.dentry === target.mount.root,
        mountId = target.mount.id,
    ).toNativeBytes()
    return if (UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_R8]).copyToUser(status)) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun readlink(regs: PtraceRegisters, process: Process): Long = readlinkAt(
    process = process,
    dirFd = AT_FDCWD,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    bufferAddress = regs[PtraceRegisters.IDX_RSI],
    bufferSize = regs[PtraceRegisters.IDX_RDX],
)

internal fun readlinkAt(regs: PtraceRegisters, process: Process): Long = readlinkAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    bufferAddress = regs[PtraceRegisters.IDX_RDX],
    bufferSize = regs[PtraceRegisters.IDX_R10],
)

private fun readlinkAt(
    process: Process,
    dirFd: Int,
    pathnameAddress: ULong,
    bufferAddress: ULong,
    bufferSize: ULong,
): Long {
    if (bufferSize == 0uL) return errno(Errno.EINVAL)
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val path = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = false,
        allowEmpty = dirFd != AT_FDCWD,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    if (inode.type != InodeType.SYMLINK) return errno(Errno.EINVAL)
    val backend = inode.backend as? SymlinkBackend ?: return errno(Errno.EINVAL)
    val target = when (val result = backend.readLink(caller, inode)) {
        is VfsResult.Ok -> result.value.copyBytes()
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val count = minOf(bufferSize, target.size.toULong()).toInt()
    return if (UserMemory(process.addressSpace, bufferAddress).copyToUser(target, size = count)) {
        path.mount.recordAccess(caller, inode)
        if (count != 0) path.notify(inode, FileSystemEvent.ACCESSED)
        count.toLong()
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun fstat(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    val caller = process.vfsOperationContext
    return try {
        copyStat(process, caller, regs[PtraceRegisters.IDX_RSI], file.inode)
    } finally {
        file.release()
    }
}

private fun statPath(
    process: Process,
    pathnameAddress: ULong,
    statAddress: ULong,
    followFinalSymlink: Boolean,
): Long {
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val path = when (val result = resolveAt(
        process = process,
        dirFd = AT_FDCWD,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = followFinalSymlink,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    return copyStat(process, caller, statAddress, inode)
}

private fun copyStat(
    process: Process,
    caller: VfsOperationContext,
    address: ULong,
    inode: Inode,
): Long {
    val attributes = when (val result = inode.attributes(caller)) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return if (UserMemory(process.addressSpace, address).copyToUser(
            LinuxStat(LinuxFileStatus.snapshot(inode, attributes)).toNativeBytes(),
        )
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}
