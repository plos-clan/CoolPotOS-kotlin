@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.CreateDisposition
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountRequest
import org.plos_clan.cpos.fs.vfs.MountResources
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.UnmountMode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.FALLOC_FL_KEEP_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.MS_SILENT
import org.plos_clan.cpos.syscall.fs.FsConstants.O_CLOEXEC
import org.plos_clan.cpos.syscall.fs.FsConstants.O_NONBLOCK
import org.plos_clan.cpos.syscall.fs.FsConstants.SUPPORTED_OPEN_FLAGS
import org.plos_clan.cpos.syscall.fs.FsConstants.S_IALLUGO
import org.plos_clan.cpos.syscall.fs.FsPathResolver.atPath
import org.plos_clan.cpos.syscall.fs.FsPathResolver.resolveAt
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters

private enum class LinuxUnmountFlag(bit: Int, val supported: Boolean = true) {
    FORCE(0),
    DETACH(1),
    EXPIRE(2, supported = false),
    NO_FOLLOW(3);

    val mask = 1u shl bit
}

private value class LinuxUnmountFlags private constructor(private val bits: UInt) {
    operator fun contains(flag: LinuxUnmountFlag): Boolean = bits and flag.mask != 0u

    val hasUnsupported: Boolean
        get() = bits and supportedMask.inv() != 0u

    companion object {
        private val supportedMask = LinuxUnmountFlag.entries.fold(0u) { bits, flag ->
            if (flag.supported) bits or flag.mask else bits
        }

        fun fromBits(bits: ULong): LinuxUnmountFlags? =
            bits.takeIf { it <= UInt.MAX_VALUE.toULong() }?.let { LinuxUnmountFlags(it.toUInt()) }
    }
}

internal fun open(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    return open(
        process = process,
        caller = process.vfsOperationContext,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RSI],
        rawMode = regs[PtraceRegisters.IDX_RDX],
    )
}

internal fun openAt(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
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
    return open(
        process = process,
        caller = caller,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RDX],
        rawMode = regs[PtraceRegisters.IDX_R10],
        directory = target.directory,
    )
}

private fun open(
    process: Process,
    caller: VfsOperationContext,
    pathname: ByteArray,
    rawFlags: ULong,
    rawMode: ULong,
    directory: VfsPath? = null,
): Long {
    if (rawFlags > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val flags = rawFlags.toInt()
    if (flags and SUPPORTED_OPEN_FLAGS.inv() != 0) {
        return errno(Errno.EINVAL)
    }
    if (flags and OpenFlags.O_TMPFILE == OpenFlags.O_TMPFILE) {
        return errno(Errno.ENOTSUP)
    }

    val pathOnly = flags and OpenFlags.O_PATH != 0
    val access = if (pathOnly) {
        AccessMode.PATH
    } else when (flags and OpenFlags.O_ACCMODE) {
        OpenFlags.O_RDONLY -> AccessMode.READ
        OpenFlags.O_WRONLY -> AccessMode.WRITE
        OpenFlags.O_RDWR -> AccessMode.READ_WRITE
        else -> return errno(Errno.EINVAL)
    }
    val create = when {
        pathOnly -> CreateDisposition.OPEN_EXISTING
        flags and OpenFlags.O_CREAT == 0 -> CreateDisposition.OPEN_EXISTING
        flags and OpenFlags.O_EXCL != 0 -> CreateDisposition.CREATE_NEW
        else -> CreateDisposition.OPEN_OR_CREATE
    }
    val context = process.context
        ?: return errno(VfsError.NOT_FOUND.errno)
    val vfsPathname = VfsPathname.fromBytes(pathname)
    val options = OpenOptions(
        access = access,
        create = create,
        createMode = FileMode(rawMode.toUInt() and S_IALLUGO and caller.fileCreationMask.inv()),
        truncate = !pathOnly && flags and OpenFlags.O_TRUNC != 0,
        append = !pathOnly && flags and OpenFlags.O_APPEND != 0,
        directoryOnly = flags and OpenFlags.O_DIRECTORY != 0,
        followFinalSymlink = flags and OpenFlags.O_NOFOLLOW == 0,
        nonBlocking = flags and OpenFlags.O_NONBLOCK != 0,
        noAtime = !pathOnly && flags and OpenFlags.O_NOATIME != 0,
    )
    val opened = if (directory == null) {
        FileSystemManager.vfs.open(caller, context, vfsPathname, options)
    } else {
        FileSystemManager.vfs.openAt(
            caller,
            context,
            directory,
            vfsPathname,
            options,
        )
    }
    val file = when (opened) {
        is VfsResult.Ok -> opened.value
        is VfsResult.Err -> return errno(opened.error.errno)
    }
    val descriptorFlags = if (flags and OpenFlags.O_CLOEXEC != 0) {
        FileDescriptorFlags.FD_CLOEXEC
    } else {
        0uL
    }
    return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
        file.release()
        errno(Errno.EMFILE)
    }
}

internal fun close(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    return when (val result = process.fdTable.close(process.vfsOperationContext, fd)) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun mount(regs: PtraceRegisters, process: Process): Long {
    if (process.euid != 0) return errno(Errno.EPERM)

    val target = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    if (target.isEmpty()) return errno(Errno.ENOENT)
    val fileSystemName = copyPath(process, regs[PtraceRegisters.IDX_RDX])
        ?: return errno(Errno.EFAULT)
    val sourceAddress = regs[PtraceRegisters.IDX_RDI]
    val source = if (sourceAddress == 0uL) null else {
        copyPath(process, sourceAddress)?.decodeToString()
            ?: return errno(Errno.EFAULT)
    }
    val dataAddress = regs[PtraceRegisters.IDX_R8]
    val data = if (dataAddress == 0uL) null else {
        UserMemory(process.addressSpace, dataAddress).copyCStringFromUser(PAGE_SIZE_BYTES.toInt())
            ?: return errno(Errno.EFAULT)
    }

    val flags = MountFlags.fromBits(regs[PtraceRegisters.IDX_R10] and MS_SILENT.inv())
        ?: return errno(Errno.EOPNOTSUPP)
    val context = process.context ?: return errno(Errno.ENOENT)
    return when (val result = FileSystemManager.vfs.mount(
        caller = process.vfsOperationContext,
        context = context,
        target = VfsPathname.fromBytes(target),
        request = MountRequest(
            fileSystemName = fileSystemName.decodeToString(),
            source = source,
            flags = flags,
            data = data,
            resources = MountResources(process.fdTable::acquire),
        ),
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun umount2(regs: PtraceRegisters, process: Process): Long {
    if (process.euid != 0) return errno(Errno.EPERM)

    val flags = LinuxUnmountFlags.fromBits(regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EINVAL)
    if (LinuxUnmountFlag.EXPIRE in flags) return errno(Errno.EOPNOTSUPP)
    if (flags.hasUnsupported) return errno(Errno.EINVAL)

    val target = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    if (target.isEmpty()) return errno(Errno.ENOENT)
    val context = process.context ?: return errno(Errno.ENOENT)
    val mode = when {
        LinuxUnmountFlag.DETACH in flags -> UnmountMode.DETACH
        LinuxUnmountFlag.FORCE in flags -> UnmountMode.FORCE
        else -> UnmountMode.REGULAR
    }
    return when (val result = FileSystemManager.vfs.unmount(
        caller = process.vfsOperationContext,
        context = context,
        target = VfsPathname.fromBytes(target),
        mode = mode,
        followFinalSymlink = LinuxUnmountFlag.NO_FOLLOW !in flags,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun fsync(regs: PtraceRegisters, process: Process): Long =
    syncFile(process, regs[PtraceRegisters.IDX_RDI], dataOnly = false)

internal fun fdatasync(regs: PtraceRegisters, process: Process): Long =
    syncFile(process, regs[PtraceRegisters.IDX_RDI], dataOnly = true)

private fun syncFile(process: Process, rawFd: ULong, dataOnly: Boolean): Long {
    val fd = fileDescriptor(rawFd) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        when (val result = file.sync(process.vfsOperationContext, dataOnly)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun truncate(regs: PtraceRegisters, process: Process): Long {
    val size = regs[PtraceRegisters.IDX_RSI]
    if (size > Long.MAX_VALUE.toULong()) return errno(Errno.EINVAL)
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val caller = process.vfsOperationContext
    val path = when (val result = resolveAt(
        process,
        AT_FDCWD,
        VfsPathname.fromBytes(pathname),
        followFinalSymlink = true,
        caller = caller,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    if (inode.type == InodeType.DIRECTORY) return errno(Errno.EISDIR)
    if (MountFlag.READ_ONLY in path.mount.flags) return errno(Errno.EROFS)
    when (val access = FileSystemManager.vfs.checkAccess(
        caller,
        inode,
        AccessPermissions.WRITE,
    )) {
        is VfsResult.Ok -> Unit
        is VfsResult.Err -> return errno(access.error.errno)
    }
    return when (val result = FileSystemManager.vfs.resize(
        caller,
        path.mount,
        inode,
        size,
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun ftruncate(regs: PtraceRegisters, process: Process): Long {
    val size = regs[PtraceRegisters.IDX_RSI]
    if (size > Long.MAX_VALUE.toULong()) return errno(Errno.EINVAL)
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        if (file.inode.type != InodeType.REGULAR) return errno(Errno.EINVAL)
        if (!file.access.canWrite) return errno(Errno.EBADF)
        when (val result = FileSystemManager.vfs.resize(
            process.vfsOperationContext,
            file.path.mount,
            file.inode,
            size,
        )) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun fallocate(regs: PtraceRegisters, process: Process): Long {
    val rawMode = regs[PtraceRegisters.IDX_RSI]
    val mode = when (rawMode) {
        0uL -> FileAllocationMode.EXTEND
        FALLOC_FL_KEEP_SIZE.toULong() -> FileAllocationMode.KEEP_SIZE
        else -> return errno(Errno.EOPNOTSUPP)
    }
    val offset = regs[PtraceRegisters.IDX_RDX]
    val length = regs[PtraceRegisters.IDX_R10]
    if (offset > Long.MAX_VALUE.toULong() || length == 0uL ||
        length > Long.MAX_VALUE.toULong() - offset
    ) {
        return errno(Errno.EINVAL)
    }
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        if (!file.access.canWrite) return errno(Errno.EBADF)
        when (file.inode.type) {
            InodeType.PIPE -> return errno(Errno.ESPIPE)
            InodeType.DIRECTORY -> return errno(Errno.EISDIR)
            InodeType.REGULAR -> Unit
            else -> return errno(Errno.ENODEV)
        }
        when (val result = FileSystemManager.vfs.allocate(
            process.vfsOperationContext,
            file.path.mount,
            file.inode,
            offset,
            length,
            mode,
        )) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun pipe(regs: PtraceRegisters, process: Process): Long =
    createPipe(process, regs[PtraceRegisters.IDX_RDI], 0uL)

internal fun pipe2(regs: PtraceRegisters, process: Process): Long = createPipe(
    process,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI],
)

private fun createPipe(process: Process, outputAddress: ULong, flags: ULong): Long {
    if (flags and (O_CLOEXEC or O_NONBLOCK).inv() != 0uL) {
        return errno(Errno.EINVAL)
    }
    val context = process.context ?: return errno(Errno.ENOENT)
    val caller = process.vfsOperationContext
    val pipe = when (val result = FileSystemManager.vfs.createPipe(
        caller,
        context,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val (readEnd, writeEnd) = pipe
    if (flags and O_NONBLOCK != 0uL) {
        readEnd.setStatusFlags(OpenFlags.O_NONBLOCK)
        writeEnd.setStatusFlags(OpenFlags.O_NONBLOCK)
    }
    val descriptorFlags = if (flags and O_CLOEXEC != 0uL) {
        FileDescriptorFlags.FD_CLOEXEC
    } else {
        0uL
    }
    val descriptors = process.fdTable.installAll(listOf(readEnd, writeEnd), descriptorFlags)
    if (descriptors == null) {
        readEnd.release()
        writeEnd.release()
        return errno(Errno.EMFILE)
    }
    val readFd = descriptors[0]
    val writeFd = descriptors[1]

    val output = ByteArray(Int.SIZE_BYTES * 2).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU32(0, readFd.toUInt())
            writeU32(Int.SIZE_BYTES, writeFd.toUInt())
        }
    }
    if (!UserMemory(process.addressSpace, outputAddress).copyToUser(output)) {
        process.fdTable.close(caller, readFd)
        process.fdTable.close(caller, writeFd)
        return errno(Errno.EFAULT)
    }
    return 0L
}
