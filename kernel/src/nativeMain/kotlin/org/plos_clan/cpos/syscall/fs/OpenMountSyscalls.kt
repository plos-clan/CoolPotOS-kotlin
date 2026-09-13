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
import org.plos_clan.cpos.fs.vfs.FileSystemConfiguration
import org.plos_clan.cpos.fs.vfs.FileSystemParameters
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.MountResources
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.PathResolution
import org.plos_clan.cpos.fs.vfs.PathResolutionBoundary
import org.plos_clan.cpos.fs.vfs.SymlinkResolution
import org.plos_clan.cpos.fs.vfs.UnmountMode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.AT_FDCWD
import org.plos_clan.cpos.syscall.fs.FsConstants.FALLOC_FL_KEEP_SIZE
import org.plos_clan.cpos.syscall.fs.FsConstants.MS_BIND
import org.plos_clan.cpos.syscall.fs.FsConstants.MS_MOVE
import org.plos_clan.cpos.syscall.fs.FsConstants.MS_SILENT
import org.plos_clan.cpos.syscall.fs.FsConstants.O_CLOEXEC
import org.plos_clan.cpos.syscall.fs.FsConstants.O_NONBLOCK
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

private enum class LinuxResolveFlag(val mask: ULong) {
    NO_XDEV(0x01uL),
    NO_MAGIC_LINKS(0x02uL),
    NO_SYMLINKS(0x04uL),
    BENEATH(0x08uL),
    IN_ROOT(0x10uL),
    CACHED(0x20uL),
}

internal class LinuxOpenHow private constructor(
    val flags: Int,
    val mode: UInt,
    val resolution: PathResolution,
) {
    fun open(process: Process, dirFd: Int, pathname: ByteArray): Long {
        val caller = process.vfsOperationContext
        val target = when (val result = atPath(
            process,
            dirFd,
            VfsPathname.fromBytes(pathname),
            caller,
            resolution,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        if (flags and OpenFlags.O_TMPFILE == OpenFlags.O_TMPFILE) {
            return errno(Errno.EOPNOTSUPP)
        }
        val pathOnly = flags and OpenFlags.O_PATH != 0
        val access = if (pathOnly) {
            AccessMode.PATH
        } else when (flags and OpenFlags.O_ACCMODE) {
            OpenFlags.O_RDONLY -> AccessMode.READ
            OpenFlags.O_WRONLY -> AccessMode.WRITE
            OpenFlags.O_RDWR -> AccessMode.READ_WRITE
            else -> error("validated open access mode is invalid")
        }
        val create = when {
            pathOnly || flags and OpenFlags.O_CREAT == 0 -> CreateDisposition.OPEN_EXISTING
            flags and OpenFlags.O_EXCL != 0 -> CreateDisposition.CREATE_NEW
            else -> CreateDisposition.OPEN_OR_CREATE
        }
        val requestedMode = mode and S_IALLUGO
        val options = OpenOptions(
            access = access,
            create = create,
            createMode = FileMode(requestedMode and caller.fileCreationMask.inv()),
            requestedCreateMode = FileMode(requestedMode),
            creationMask = caller.fileCreationMask,
            truncate = !pathOnly && flags and OpenFlags.O_TRUNC != 0,
            append = !pathOnly && flags and OpenFlags.O_APPEND != 0,
            directoryOnly = flags and OpenFlags.O_DIRECTORY != 0,
            followFinalSymlink = flags and OpenFlags.O_NOFOLLOW == 0,
            nonBlocking = flags and OpenFlags.O_NONBLOCK != 0,
            noControllingTerminal = flags and OpenFlags.O_NOCTTY != 0,
            noAtime = !pathOnly && flags and OpenFlags.O_NOATIME != 0,
            resolution = resolution,
        )
        val file = when (val result = FileSystemManager.vfs.openAt(
            target.caller,
            target.context,
            target.directory,
            target.pathname,
            options,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
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

    companion object {
        const val NATIVE_SIZE = ULong.SIZE_BYTES * 3
        private const val PATH_ONLY_FLAGS = OpenFlags.O_DIRECTORY or OpenFlags.O_NOFOLLOW or
            OpenFlags.O_PATH or OpenFlags.O_CLOEXEC
        private const val CREATE_DIRECTORY_FLAGS = OpenFlags.O_CREAT or OpenFlags.O_DIRECTORY
        private const val SUPPORTED_FLAGS =
            OpenFlags.O_ACCMODE or OpenFlags.O_CREAT or OpenFlags.O_EXCL or
                OpenFlags.O_NOCTTY or OpenFlags.O_TRUNC or OpenFlags.O_APPEND or
                OpenFlags.O_NONBLOCK or OpenFlags.O_DSYNC or OpenFlags.O_SYNC or
                OpenFlags.O_ASYNC or OpenFlags.O_DIRECT or OpenFlags.O_LARGEFILE or
                OpenFlags.O_DIRECTORY or OpenFlags.O_NOFOLLOW or OpenFlags.O_NOATIME or
                OpenFlags.O_CLOEXEC or OpenFlags.O_PATH or OpenFlags.O_TMPFILE
        private val supportedResolution = LinuxResolveFlag.entries.fold(0uL) { bits, flag ->
            bits or flag.mask
        }

        fun legacy(rawFlags: ULong, mode: ULong): LinuxOpenHow? {
            var flags = rawFlags.toUInt().toInt() and SUPPORTED_FLAGS
            if (flags and OpenFlags.O_PATH != 0) flags = flags and PATH_ONLY_FLAGS
            return decode(flags.toUInt().toULong(), mode, 0uL, strictMode = false)
        }

        fun decode(bytes: ByteArray): LinuxOpenHow? {
            require(bytes.size >= NATIVE_SIZE)
            val input = LittleEndianBuffer(bytes)
            return decode(
                input.readU64(0),
                input.readU64(ULong.SIZE_BYTES),
                input.readU64(ULong.SIZE_BYTES * 2),
                strictMode = true,
            )
        }

        private fun decode(
            rawFlags: ULong,
            rawMode: ULong,
            rawResolution: ULong,
            strictMode: Boolean,
        ): LinuxOpenHow? {
            if (rawFlags > UInt.MAX_VALUE.toULong()) return null
            val flags = rawFlags.toInt()
            if (flags and SUPPORTED_FLAGS.inv() != 0) return null
            if (flags and OpenFlags.O_PATH != 0 && flags and PATH_ONLY_FLAGS.inv() != 0) {
                return null
            }
            val temporary = flags and OpenFlags.O_TMPFILE == OpenFlags.O_TMPFILE
            if (temporary && flags and OpenFlags.O_ACCMODE == OpenFlags.O_RDONLY) return null
            if (flags and CREATE_DIRECTORY_FLAGS == CREATE_DIRECTORY_FLAGS) {
                return null
            }
            val creates = temporary || flags and OpenFlags.O_CREAT != 0
            if (strictMode && (rawMode and S_IALLUGO.toULong().inv() != 0uL ||
                    !creates && rawMode != 0uL)
            ) {
                return null
            }
            if (rawResolution and supportedResolution.inv() != 0uL) return null
            val beneath = rawResolution and LinuxResolveFlag.BENEATH.mask != 0uL
            val inRoot = rawResolution and LinuxResolveFlag.IN_ROOT.mask != 0uL
            if (beneath && inRoot) return null

            val boundary = when {
                beneath -> PathResolutionBoundary.BENEATH
                inRoot -> PathResolutionBoundary.IN_ROOT
                else -> PathResolutionBoundary.NONE
            }
            val symlinks = when {
                rawResolution and LinuxResolveFlag.NO_SYMLINKS.mask != 0uL ->
                    SymlinkResolution.NO_SYMLINKS
                rawResolution and LinuxResolveFlag.NO_MAGIC_LINKS.mask != 0uL ->
                    SymlinkResolution.NO_MAGIC_LINKS
                else -> SymlinkResolution.FOLLOW
            }
            val resolution = if (rawResolution == 0uL) {
                PathResolution.DEFAULT
            } else {
                PathResolution(
                    boundary = boundary,
                    symlinks = symlinks,
                    allowMountCrossing =
                        rawResolution and LinuxResolveFlag.NO_XDEV.mask == 0uL,
                    cachedOnly = rawResolution and LinuxResolveFlag.CACHED.mask != 0uL,
                )
            }
            return LinuxOpenHow(flags, rawMode.toUInt() and S_IALLUGO, resolution)
        }
    }
}

internal fun open(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val how = LinuxOpenHow.legacy(
        regs[PtraceRegisters.IDX_RSI],
        regs[PtraceRegisters.IDX_RDX],
    ) ?: return errno(Errno.EINVAL)
    return how.open(process, AT_FDCWD, pathname)
}

internal fun openAt(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val how = LinuxOpenHow.legacy(
        regs[PtraceRegisters.IDX_RDX],
        regs[PtraceRegisters.IDX_R10],
    ) ?: return errno(Errno.EINVAL)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    return how.open(process, dirFd, pathname)
}

internal fun openAt2(regs: PtraceRegisters, process: Process): Long {
    val size = regs[PtraceRegisters.IDX_R10]
    if (size < LinuxOpenHow.NATIVE_SIZE.toULong()) return errno(Errno.EINVAL)
    if (size > PAGE_SIZE_BYTES) return errno(Errno.E2BIG)
    val bytes = UserMemory(
        process.addressSpace,
        regs[PtraceRegisters.IDX_RDX],
    ).copyFromUser(size.toInt()) ?: return errno(Errno.EFAULT)
    for (index in LinuxOpenHow.NATIVE_SIZE until bytes.size) {
        if (bytes[index] != 0.toByte()) return errno(Errno.E2BIG)
    }
    val how = LinuxOpenHow.decode(bytes) ?: return errno(Errno.EINVAL)
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    return how.open(process, dirFd, pathname)
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
    if (process.credentials.userIds.effective != 0) return errno(Errno.EPERM)

    val target = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    if (target.isEmpty()) return errno(Errno.ENOENT)
    val rawFlags = regs[PtraceRegisters.IDX_R10]
    val sourceAddress = regs[PtraceRegisters.IDX_RDI]
    val source = if (sourceAddress == 0uL) null else {
        copyPath(process, sourceAddress) ?: return errno(Errno.EFAULT)
    }
    val context = process.context ?: return errno(Errno.ENOENT)
    if (rawFlags and MS_MOVE != 0uL) {
        if (rawFlags and (MS_MOVE or MS_SILENT).inv() != 0uL) {
            return errno(Errno.EINVAL)
        }
        val moveSource = source?.takeIf(ByteArray::isNotEmpty)
            ?: return errno(Errno.ENOENT)
        return when (val result = FileSystemManager.vfs.moveMount(
            caller = process.vfsOperationContext,
            context = context,
            source = VfsPathname.fromBytes(moveSource),
            target = VfsPathname.fromBytes(target),
        )) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }
    if (rawFlags and MS_BIND != 0uL) {
        if (rawFlags and (MS_BIND or MS_SILENT).inv() != 0uL) {
            return errno(Errno.EINVAL)
        }
        val bindSource = source?.takeIf(ByteArray::isNotEmpty)
            ?: return errno(Errno.ENOENT)
        return when (val result = FileSystemManager.vfs.bindMount(
            caller = process.vfsOperationContext,
            context = context,
            source = VfsPathname.fromBytes(bindSource),
            target = VfsPathname.fromBytes(target),
        )) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    val fileSystemName = copyPath(process, regs[PtraceRegisters.IDX_RDX])
        ?: return errno(Errno.EFAULT)
    val dataAddress = regs[PtraceRegisters.IDX_R8]
    val data = if (dataAddress == 0uL) null else {
        UserMemory(process.addressSpace, dataAddress).copyCStringFromUser(PAGE_SIZE_BYTES.toInt())
            ?: return errno(Errno.EFAULT)
    }

    val flags = MountFlags.fromBits(rawFlags and MS_SILENT.inv())
        ?: return errno(Errno.EOPNOTSUPP)
    return when (val result = FileSystemManager.vfs.mount(
        caller = process.vfsOperationContext,
        context = context,
        target = VfsPathname.fromBytes(target),
        configuration = FileSystemConfiguration(
            fileSystemName = fileSystemName.decodeToString(),
            source = source?.decodeToString(),
            flags = flags,
            parameters = FileSystemParameters.fromMountData(data),
            resources = MountResources(process.fdTable::acquire),
        ),
    )) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun umount2(regs: PtraceRegisters, process: Process): Long {
    if (process.credentials.userIds.effective != 0) return errno(Errno.EPERM)

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
        path,
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
            file.path,
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
        FileAllocationMode.PUNCH_HOLE.bits.toULong() -> FileAllocationMode.PUNCH_HOLE
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
        when (val result = file.allocate(
            process.vfsOperationContext,
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
