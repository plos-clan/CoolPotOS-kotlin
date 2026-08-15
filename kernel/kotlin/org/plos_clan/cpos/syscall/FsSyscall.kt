@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.CreateDisposition
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.FileAllocationMode
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeMetadata
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.IoResult
import org.plos_clan.cpos.fs.MountFlags
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.OpenFileDescription
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.SeekOrigin
import org.plos_clan.cpos.fs.SymlinkBackend
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsPath
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.PtraceRegisters

private const val IO_CHUNK_SIZE = 64 * 1024
private const val MAX_RW_COUNT = 0x7ffff000uL
private const val IO_VECTOR_SIZE = ULong.SIZE_BYTES * 2
private const val MAX_IO_VECTORS = 1024
private const val AT_FDCWD = -100
private const val AT_SYMLINK_NOFOLLOW = 0x100
private const val AT_EACCESS = 0x200
private const val AT_NO_AUTOMOUNT = 0x800
private const val AT_EMPTY_PATH = 0x1000
private const val AT_STATX_FORCE_SYNC = 0x2000
private const val AT_STATX_DONT_SYNC = 0x4000
private const val AT_STATX_SYNC_TYPE = AT_STATX_FORCE_SYNC or AT_STATX_DONT_SYNC
private const val O_CLOEXEC = 0x0008_0000uL
private const val O_NONBLOCK = 0x0000_0800uL
private const val POLL_FD_SIZE = 8
private const val MAX_POLL_FDS = 1024
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000uL
private const val SUPPORTED_OPEN_FLAGS =
    OpenFlags.O_ACCMODE or OpenFlags.O_CREAT or OpenFlags.O_EXCL or
            OpenFlags.O_NOCTTY or OpenFlags.O_TRUNC or OpenFlags.O_APPEND or
            OpenFlags.O_NONBLOCK or OpenFlags.O_DSYNC or OpenFlags.O_SYNC or
            OpenFlags.O_ASYNC or OpenFlags.O_DIRECT or OpenFlags.O_LARGEFILE or
            OpenFlags.O_DIRECTORY or OpenFlags.O_NOFOLLOW or OpenFlags.O_NOATIME or
            OpenFlags.O_CLOEXEC or OpenFlags.O_PATH or OpenFlags.O_TMPFILE

private const val F_DUPFD = 0
private const val F_GETFD = 1
private const val F_SETFD = 2
private const val F_GETFL = 3
private const val F_SETFL = 4
private const val F_GETOWN = 9
private const val F_SETOWN = 8
private const val F_DUPFD_CLOEXEC = 1_030
private const val F_GETFD_FLAGS = FileDescriptorFlags.FD_CLOEXEC

private const val STAT_SIZE = 144
private const val STATX_SIZE = 256
private const val STATFS_SIZE = 120
private const val STAT_BLKSIZE = 4096uL
private const val DIRENT64_HEADER_SIZE = 19
private const val DIRENT64_ALIGNMENT = 8
private const val DIRENT64_MIN_SIZE = 24

private const val S_IFIFO = 0x1000u
private const val S_IFCHR = 0x2000u
private const val S_IFDIR = 0x4000u
private const val S_IFBLK = 0x6000u
private const val S_IFREG = 0x8000u
private const val S_IFLNK = 0xA000u
private const val S_IFSOCK = 0xC000u
private const val S_ISGID = 0x400u
private const val S_IALLUGO = 0xFFFu

private const val FALLOC_FL_KEEP_SIZE = 0x01

private const val STATX_SUPPORTED_FIELDS = 0x71fu
private const val STATX_RESERVED = 0x8000_0000u

private const val ST_RDONLY = 0x1uL
private const val ST_NOSUID = 0x2uL
private const val ST_NODEV = 0x4uL
private const val ST_NOEXEC = 0x8uL

private data class LinuxFileStatus(
    val inodeId: ULong,
    val type: InodeType,
    val metadata: InodeMetadata,
    val blocks: ULong,
) {
    val mode: UInt
        get() = metadata.mode.bits or when (type) {
            InodeType.REGULAR -> S_IFREG
            InodeType.DIRECTORY -> S_IFDIR
            InodeType.SYMLINK -> S_IFLNK
            InodeType.CHARACTER_DEVICE -> S_IFCHR
            InodeType.BLOCK_DEVICE -> S_IFBLK
            InodeType.PIPE -> S_IFIFO
            InodeType.SOCKET -> S_IFSOCK
        }

    val deviceMajor: UInt
        get() = (metadata.deviceNumber shr 8 and 0xfffuL).toUInt()

    val deviceMinor: UInt
        get() = ((metadata.deviceNumber and 0xffuL) or
            (metadata.deviceNumber shr 12 and 0xfffff00uL)).toUInt()

    companion object {
        fun snapshot(inode: Inode) = LinuxFileStatus(
            inodeId = inode.id.value,
            type = inode.type,
            metadata = inode.metadata(),
            blocks = inode.backend.allocatedBlocks(inode),
        )
    }
}

private class LinuxStat(private val status: LinuxFileStatus) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STAT_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, 0uL) // st_dev
            writeU64(8, status.inodeId)
            writeU64(16, status.metadata.linkCount.toULong())
            writeU32(24, status.mode)
            writeU32(28, status.metadata.uid)
            writeU32(32, status.metadata.gid)
            writeU32(36, 0u) // __pad0
            writeU64(40, status.metadata.deviceNumber)
            writeU64(48, status.metadata.size)
            writeU64(56, STAT_BLKSIZE)
            writeU64(64, status.blocks)
        }
    }
}

private class LinuxStatx(private val status: LinuxFileStatus) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STATX_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU32(0, STATX_SUPPORTED_FIELDS)
            writeU32(4, STAT_BLKSIZE.toUInt())
            writeU32(16, status.metadata.linkCount)
            writeU32(20, status.metadata.uid)
            writeU32(24, status.metadata.gid)
            writeU16(28, status.mode.toUShort())
            writeU64(32, status.inodeId)
            writeU64(40, status.metadata.size)
            writeU64(48, status.blocks)
            writeU32(128, status.deviceMajor)
            writeU32(132, status.deviceMinor)
        }
    }
}

private class LinuxStatFs(private val path: VfsPath) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STATFS_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, path.mount.superBlock.type.magic)
            writeU64(8, STAT_BLKSIZE)
            writeU64(64, 255uL)
            writeU64(72, STAT_BLKSIZE)
            writeU64(80, path.mount.flags.toStatFsFlags())
        }
    }

    private fun MountFlags.toStatFsFlags(): ULong {
        var result = 0uL
        if (MountFlags.READ_ONLY in this) result = result or ST_RDONLY
        if (MountFlags.NO_SUID in this) result = result or ST_NOSUID
        if (MountFlags.NO_DEVICE in this) result = result or ST_NODEV
        if (MountFlags.NO_EXEC in this) result = result or ST_NOEXEC
        return result
    }
}

private class LinuxDirent64(
    private val entry: DirectoryEntry,
    private val nextOffset: Long,
) : NativeStruct {
    private val name = entry.name.copyBytes()

    val recordSize: Int =
        (DIRENT64_HEADER_SIZE + name.size + 1 + DIRENT64_ALIGNMENT - 1) /
            DIRENT64_ALIGNMENT * DIRENT64_ALIGNMENT

    override fun toNativeBytes(): ByteArray = ByteArray(recordSize).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, entry.inodeId.value)
            writeU64(8, nextOffset.toULong())
            writeU16(16, recordSize.toUShort())
        }
        buffer[18] = entry.type.directoryEntryType
        name.copyInto(buffer, DIRENT64_HEADER_SIZE)
    }

    private val InodeType.directoryEntryType: Byte
        get() = when (this) {
            InodeType.PIPE -> 1
            InodeType.CHARACTER_DEVICE -> 2
            InodeType.DIRECTORY -> 4
            InodeType.BLOCK_DEVICE -> 6
            InodeType.REGULAR -> 8
            InodeType.SYMLINK -> 10
            InodeType.SOCKET -> 12
        }
}

internal fun open(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    return open(
        process = process,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RSI],
        rawMode = regs[PtraceRegisters.IDX_RDX],
    )
}

internal fun openAt(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val directory = if (pathname.firstOrNull() == '/'.code.toByte() || dirFd == AT_FDCWD) {
        null
    } else {
        if (dirFd < 0) return errno(Errno.EBADF)
        val file = process.fdTable.acquire(dirFd) ?: return errno(Errno.EBADF)
        try {
            if (file.inode.type != InodeType.DIRECTORY) return errno(Errno.ENOTDIR)
            file.path
        } finally {
            file.release()
        }
    }
    return open(
        process = process,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RDX],
        rawMode = regs[PtraceRegisters.IDX_R10],
        directory = directory,
    )
}

private fun open(
    process: Process,
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
        createMode = FileMode(rawMode.toUInt() and 0x1ffu),
        truncate = !pathOnly && flags and OpenFlags.O_TRUNC != 0,
        append = !pathOnly && flags and OpenFlags.O_APPEND != 0,
        directoryOnly = flags and OpenFlags.O_DIRECTORY != 0,
        followFinalSymlink = flags and OpenFlags.O_NOFOLLOW == 0,
    )
    val opened = if (directory == null) {
        FileSystemManager.vfs.open(context, vfsPathname, options)
    } else {
        FileSystemManager.vfs.openAt(context, directory, vfsPathname, options)
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
    return if (process.fdTable.close(fd)) 0L else errno(Errno.EBADF)
}

internal fun fsync(regs: PtraceRegisters, process: Process): Long =
    syncFile(process, regs[PtraceRegisters.IDX_RDI], dataOnly = false)

internal fun fdatasync(regs: PtraceRegisters, process: Process): Long =
    syncFile(process, regs[PtraceRegisters.IDX_RDI], dataOnly = true)

private fun syncFile(process: Process, rawFd: ULong, dataOnly: Boolean): Long {
    val fd = fileDescriptor(rawFd) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        when (val result = file.sync(dataOnly)) {
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
    val path = when (val result = resolveAt(
        process,
        AT_FDCWD,
        VfsPathname.fromBytes(pathname),
        followFinalSymlink = true,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    if (inode.type == InodeType.DIRECTORY) return errno(Errno.EISDIR)
    if (MountFlags.READ_ONLY in path.mount.flags) return errno(Errno.EROFS)
    if (!process.mayWrite(inode.metadata())) return errno(Errno.EACCES)
    return when (val result = FileSystemManager.vfs.resize(path, size)) {
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
        when (val result = FileSystemManager.vfs.resize(file.path, size)) {
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
        when (val result = FileSystemManager.vfs.allocate(file.path, offset, length, mode)) {
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
    val pipe = when (val result = FileSystemManager.vfs.createPipe(context)) {
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
    val readFd = process.fdTable.install(readEnd, descriptorFlags)
    val writeFd = process.fdTable.install(writeEnd, descriptorFlags)
    if (readFd == null || writeFd == null) {
        readFd?.let(process.fdTable::close)
        writeFd?.let(process.fdTable::close)
        if (readFd == null) readEnd.release()
        if (writeFd == null) writeEnd.release()
        return errno(Errno.EMFILE)
    }

    val output = ByteArray(Int.SIZE_BYTES * 2).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU32(0, readFd.toUInt())
            writeU32(Int.SIZE_BYTES, writeFd.toUInt())
        }
    }
    if (!UserMemory(process.addressSpace, outputAddress).copyToUser(output)) {
        process.fdTable.close(readFd)
        process.fdTable.close(writeFd)
        return errno(Errno.EFAULT)
    }
    return 0L
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
    return try {
        changeOwner(process, file.path, regs[PtraceRegisters.IDX_RSI], regs[PtraceRegisters.IDX_RDX])
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
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return changeOwner(
        process,
        target,
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
    val target = when (val result = resolveAt(
        process,
        AT_FDCWD,
        VfsPathname.fromBytes(pathname),
        followFinalSymlink,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return changeOwner(process, target, uid, gid)
}

private fun changeOwner(process: Process, path: VfsPath, uid: ULong, gid: ULong): Long {
    if (process.euid != 0) return errno(Errno.EPERM)
    if (MountFlags.READ_ONLY in path.mount.flags) return errno(Errno.EROFS)
    if (uid > UInt.MAX_VALUE.toULong() || gid > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    inode.updateMetadata { metadata ->
        metadata.copy(
            uid = uid.toUInt().takeUnless { it == UInt.MAX_VALUE } ?: metadata.uid,
            gid = gid.toUInt().takeUnless { it == UInt.MAX_VALUE } ?: metadata.gid,
        )
    }
    return 0L
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
    return try {
        if (file.access == AccessMode.PATH) errno(Errno.EBADF)
        else changeMode(process, file.path, regs[PtraceRegisters.IDX_RSI])
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
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    if (target.inode?.type == InodeType.SYMLINK) return errno(Errno.EOPNOTSUPP)
    return changeMode(process, target, rawMode)
}

private fun changeMode(process: Process, path: VfsPath, rawMode: ULong): Long {
    if (MountFlags.READ_ONLY in path.mount.flags) return errno(Errno.EROFS)
    val inode = path.inode ?: return errno(Errno.ENOENT)
    val metadata = inode.metadata()
    if (process.euid != 0 && process.fsuid.toUInt() != metadata.uid) {
        return errno(Errno.EPERM)
    }

    var mode = rawMode.toUInt() and S_IALLUGO
    if (process.euid != 0 && metadata.gid != process.fsgid.toUInt()) {
        mode = mode and S_ISGID.inv()
    }
    return when (val result = FileSystemManager.vfs.setMode(path, FileMode(mode))) {
        is VfsResult.Ok -> 0L
        is VfsResult.Err -> errno(result.error.errno)
    }
}

private fun Process.mayWrite(metadata: InodeMetadata): Boolean {
    if (euid == 0) return true
    val shift = when {
        fsuid.toUInt() == metadata.uid -> 6
        fsgid.toUInt() == metadata.gid -> 3
        else -> 0
    }
    return metadata.mode.bits shr shift and 0x2u != 0u
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
    val path = when (val result = FileSystemManager.vfs.resolve(
        process.getFSContext(),
        VfsPathname.fromBytes(pathname),
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    return copyStatFs(process, regs[PtraceRegisters.IDX_RSI], path)
}

internal fun fstatfs(regs: PtraceRegisters, process: Process): Long {
    val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
    return try {
        copyStatFs(process, regs[PtraceRegisters.IDX_RSI], file.path)
    } finally {
        file.release()
    }
}

private fun copyStatFs(process: Process, address: ULong, path: VfsPath): Long =
    if (UserMemory(process.addressSpace, address).copyToUser(LinuxStatFs(path).toNativeBytes())) {
        0L
    } else {
        errno(Errno.EFAULT)
    }

internal fun access(regs: PtraceRegisters, process: Process): Long {
    val mode = regs[PtraceRegisters.IDX_RSI]
    if (mode > 0x7uL) {
        return errno(Errno.EINVAL)
    }
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val context = process.context ?: return errno(Errno.ENOENT)
    val path = when (
        val result = FileSystemManager.vfs.resolve(
            context = context,
            pathname = VfsPathname.fromBytes(pathname),
        )
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    val permissionBits = inode.metadata().mode.bits
    return if (mode and 0x1uL != 0uL && permissionBits and 0x49u == 0u) {
        errno(Errno.EACCES)
    } else {
        0L
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
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    val permissions = inode.metadata().mode.bits
    return if (mode and 0x1uL != 0uL && permissions and 0x49u == 0u) {
        errno(Errno.EACCES)
    } else {
        0L
    }
}

internal fun newFstatAt(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R10]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_NO_AUTOMOUNT or AT_EMPTY_PATH).toULong()
    if (flags and supportedFlags.inv() != 0uL || flags > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }

    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val target = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return copyStat(process, regs[PtraceRegisters.IDX_RDX], inode)
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
    val target = when (val result = resolveAt(
        process = process,
        dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        allowEmpty = flags.toInt() and AT_EMPTY_PATH != 0,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    if (flags.toInt() and AT_STATX_FORCE_SYNC != 0) {
        when (val result = inode.backend.sync(inode, dataOnly = false)) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val status = LinuxStatx(LinuxFileStatus.snapshot(inode)).toNativeBytes()
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
    val path = when (val result = resolveAt(
        process = process,
        dirFd = dirFd,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = false,
        allowEmpty = dirFd != AT_FDCWD,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    if (inode.type != InodeType.SYMLINK) return errno(Errno.EINVAL)
    val backend = inode.backend as? SymlinkBackend ?: return errno(Errno.EINVAL)
    val target = when (val result = backend.readLink(inode)) {
        is VfsResult.Ok -> result.value.copyBytes()
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val count = minOf(bufferSize, target.size.toULong()).toInt()
    return if (UserMemory(process.addressSpace, bufferAddress).copyToUser(target, size = count)) {
        count.toLong()
    } else {
        errno(Errno.EFAULT)
    }
}

private fun resolveAt(
    process: Process,
    dirFd: Int,
    pathname: VfsPathname,
    followFinalSymlink: Boolean,
    allowEmpty: Boolean = false,
): VfsResult<VfsPath> {
    if (pathname.size == 0 && !allowEmpty) return VfsResult.Err(VfsError.NOT_FOUND)
    val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
    if (pathname.isAbsolute || dirFd == AT_FDCWD) {
        return FileSystemManager.vfs.resolveAt(
            context = context,
            directory = context.workingDirectory,
            pathname = pathname,
            followFinalSymlink = followFinalSymlink,
            allowEmpty = allowEmpty,
        )
    }
    if (dirFd < 0) return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
    val directory = process.fdTable.acquire(dirFd)
        ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
    return try {
        FileSystemManager.vfs.resolveAt(
            context = context,
            directory = directory.path,
            pathname = pathname,
            followFinalSymlink = followFinalSymlink,
            allowEmpty = allowEmpty,
        )
    } finally {
        directory.release()
    }
}

internal fun fstat(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        copyStat(process, regs[PtraceRegisters.IDX_RSI], file.inode)
    } finally {
        file.release()
    }
}

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

private fun statPath(
    process: Process,
    pathnameAddress: ULong,
    statAddress: ULong,
    followFinalSymlink: Boolean,
): Long {
    val pathname = copyPath(process, pathnameAddress) ?: return errno(Errno.EFAULT)
    val path = when (val result = resolveAt(
        process = process,
        dirFd = AT_FDCWD,
        pathname = VfsPathname.fromBytes(pathname),
        followFinalSymlink = followFinalSymlink,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    return copyStat(process, statAddress, inode)
}

private fun copyStat(process: Process, address: ULong, inode: Inode): Long =
    if (UserMemory(process.addressSpace, address).copyToUser(
            LinuxStat(LinuxFileStatus.snapshot(inode)).toNativeBytes(),
        )
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }

internal fun lseek(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val whenceValue = regs[PtraceRegisters.IDX_RDX]
    if (whenceValue > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val origin = when (whenceValue.toInt()) {
        0 -> SeekOrigin.START
        1 -> SeekOrigin.CURRENT
        2 -> SeekOrigin.END
        else -> return errno(Errno.EINVAL)
    }
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        when (file.inode.type) {
            InodeType.CHARACTER_DEVICE,
            InodeType.BLOCK_DEVICE,
            InodeType.PIPE,
            InodeType.SOCKET,
            -> errno(Errno.ESPIPE)
            else -> when (val result = file.seek(regs[PtraceRegisters.IDX_RSI].toLong(), origin)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
    } finally {
        file.release()
    }
}

internal fun dup(regs: PtraceRegisters, process: Process): Long {
    val oldFd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(oldFd) ?: return errno(Errno.EBADF)
    val newFd = process.fdTable.install(file, 0uL)
    if (newFd == null) {
        file.release()
        return errno(Errno.EMFILE)
    }
    return newFd.toLong()
}

internal fun dup2(regs: PtraceRegisters, process: Process): Long {
    val oldFd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val newFd = fileDescriptor(regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EBADF)
    return if (process.fdTable.dup2(oldFd, newFd)) {
        newFd.toLong()
    } else {
        errno(Errno.EBADF)
    }
}

internal fun fcntl(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val command = regs[PtraceRegisters.IDX_RSI]
    if (command > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val argument = regs[PtraceRegisters.IDX_RDX]
    return when (command.toInt()) {
        F_DUPFD,
        F_DUPFD_CLOEXEC,
        -> {
            if (argument > Int.MAX_VALUE.toULong()) {
                return errno(Errno.EINVAL)
            }
            val flags = if (command.toInt() == F_DUPFD_CLOEXEC) F_GETFD_FLAGS else 0uL
            process.fdTable.duplicate(fd, argument.toInt(), flags)?.toLong()
                ?: errno(Errno.EBADF)
        }

        F_GETFD -> process.fdTable.descriptorFlags(fd)?.toLong() ?: errno(Errno.EBADF)

        F_SETFD -> if (process.fdTable.setDescriptorFlags(fd, argument and F_GETFD_FLAGS)) {
            0L
        } else {
            errno(Errno.EBADF)
        }

        F_GETFL -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            try {
                val access = when (file.access) {
                    AccessMode.READ -> OpenFlags.O_RDONLY
                    AccessMode.WRITE -> OpenFlags.O_WRONLY
                    AccessMode.READ_WRITE -> OpenFlags.O_RDWR
                    AccessMode.PATH -> OpenFlags.O_PATH
                }
                (access or file.getStatusFlags() or OpenFlags.O_LARGEFILE).toLong()
            } finally {
                file.release()
            }
        }

        F_SETFL -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            try {
                file.setStatusFlags(argument.toInt())
                0L
            } finally {
                file.release()
            }
        }

        F_GETOWN,
        F_SETOWN,
        -> if (process.fdTable.contains(fd)) 0L else errno(Errno.EBADF)

        else -> errno(Errno.EINVAL)
    }
}

internal fun poll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) {
        return errno(Errno.EINVAL)
    }

    val count = countValue.toInt()
    val byteCount = count * POLL_FD_SIZE
    val userFds = UserMemory(
        process.addressSpace,
        regs[PtraceRegisters.IDX_RDI],
    )
    val descriptors = userFds.copyFromUser(byteCount)
        ?: return errno(Errno.EFAULT)
    val timeoutMilliseconds = regs[PtraceRegisters.IDX_RDX].toInt()
    if (timeoutMilliseconds > 0 && !TscClock.isReady) {
        return errno(Errno.EIO)
    }

    val timeoutNanoseconds = if (timeoutMilliseconds > 0) {
        timeoutMilliseconds.toULong() * NANOSECONDS_PER_MILLISECOND
    } else {
        0uL
    }
    val startTime = if (timeoutMilliseconds > 0) TscClock.nanoTime() else 0uL

    while (true) {
        val ready = scanPollDescriptors(process, descriptors, count)
        val timedOut = timeoutMilliseconds == 0 ||
            (timeoutMilliseconds > 0 && TscClock.nanoTime() - startTime >= timeoutNanoseconds)
        if (ready != 0 || timedOut) {
            return if (userFds.copyToUser(descriptors)) ready.toLong()
            else errno(Errno.EFAULT)
        }

        Scheduler.yieldCurrent()
        bridge.wait_for_interrupt()
    }
}

internal fun pselect6(regs: PtraceRegisters, process: Process): Long {
    val nfdsValue = regs[PtraceRegisters.IDX_RDI]
    if (nfdsValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val nfds = nfdsValue.toInt()
    val setSize = ((nfds + Long.SIZE_BITS - 1) / Long.SIZE_BITS) * ULong.SIZE_BYTES
    val requestedRead = copyFdSet(process, regs[PtraceRegisters.IDX_RSI], setSize)
        ?: return errno(Errno.EFAULT)
    val requestedWrite = copyFdSet(process, regs[PtraceRegisters.IDX_RDX], setSize)
        ?: return errno(Errno.EFAULT)
    val requestedExcept = copyFdSet(process, regs[PtraceRegisters.IDX_R10], setSize)
        ?: return errno(Errno.EFAULT)
    val timeoutAddress = regs[PtraceRegisters.IDX_R8]
    val timeout = if (timeoutAddress == 0uL) {
        null
    } else {
        when (val result = readPselectTimeout(process, timeoutAddress)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val signalMaskAddress = regs[PtraceRegisters.IDX_R9]
    val signalMask = if (signalMaskAddress == 0uL) {
        null
    } else {
        when (val result = readPselectSignalMask(process, signalMaskAddress)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val readyRead = ByteArray(setSize)
    val readyWrite = ByteArray(setSize)
    val readyExcept = ByteArray(setSize)
    val previousMask = process.signalMask
    signalMask?.let { process.signalMask = it }
    try {
        val deadline = timeout?.let(::timeoutDeadline)
        while (true) {
            requestedRead.copyInto(readyRead)
            requestedWrite.copyInto(readyWrite)
            requestedExcept.copyInto(readyExcept)
            val ready = scanSelectDescriptors(process, nfds, readyRead, readyWrite, readyExcept)
            if (ready < 0) return errno(-ready)
            val expired = deadline != null && TscClock.nanoTime() >= deadline
            if (ready != 0 || timeout?.isZero == true || expired) {
                if (!copyFdSet(process, regs[PtraceRegisters.IDX_RSI], readyRead, setSize) ||
                    !copyFdSet(process, regs[PtraceRegisters.IDX_RDX], readyWrite, setSize) ||
                    !copyFdSet(process, regs[PtraceRegisters.IDX_R10], readyExcept, setSize)
                ) return errno(Errno.EFAULT)
                return ready.toLong()
            }
            Scheduler.yieldCurrent()
            bridge.wait_for_interrupt()
        }
    } finally {
        process.signalMask = previousMask
    }
}

private fun copyFdSet(process: Process, address: ULong, size: Int): ByteArray? =
    if (address == 0uL || size == 0) ByteArray(size)
    else UserMemory(process.addressSpace, address).copyFromUser(size)

private fun copyFdSet(process: Process, address: ULong, value: ByteArray, size: Int): Boolean =
    address == 0uL || size == 0 || UserMemory(process.addressSpace, address).copyToUser(value)

private data class SelectTimeout(val seconds: Long, val nanoseconds: Long) {
    val isZero: Boolean get() = seconds == 0L && nanoseconds == 0L
}

private fun readPselectTimeout(process: Process, address: ULong): VfsResult<SelectTimeout> {
    val bytes = UserMemory(process.addressSpace, address).copyFromUser(TimeSpec.NATIVE_SIZE)
        ?: return VfsResult.Err(VfsError.FAULT)
    val value = TimeSpec(0, 0)
    if (!value.updateFromNativeBytes(bytes)) return VfsResult.Err(VfsError.FAULT)
    if (value.sec < 0 || value.nsec !in 0 until 1_000_000_000L) {
        return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    }
    return VfsResult.Ok(SelectTimeout(value.sec, value.nsec))
}

private fun readPselectSignalMask(process: Process, address: ULong): VfsResult<ULong> {
    val descriptor = UserMemory(process.addressSpace, address).copyFromUser(ULong.SIZE_BYTES * 2)
        ?: return VfsResult.Err(VfsError.FAULT)
    val input = LittleEndianBuffer(descriptor)
    val signalSet = input.readU64(0)
    val size = input.readU64(ULong.SIZE_BYTES)
    if (signalSet == 0uL) return VfsResult.Ok(process.signalMask)
    if (size != ULong.SIZE_BYTES.toULong()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    val mask = UserMemory(process.addressSpace, signalSet).copyFromUser(ULong.SIZE_BYTES)
        ?: return VfsResult.Err(VfsError.FAULT)
    return VfsResult.Ok(LittleEndianBuffer(mask).readU64(0))
}

private fun timeoutDeadline(timeout: SelectTimeout): ULong {
    val seconds = timeout.seconds.toULong()
    val duration = if (seconds > (ULong.MAX_VALUE - timeout.nanoseconds.toULong()) / 1_000_000_000uL) {
        ULong.MAX_VALUE
    } else {
        seconds * 1_000_000_000uL + timeout.nanoseconds.toULong()
    }
    val now = TscClock.nanoTime()
    return if (duration > ULong.MAX_VALUE - now) ULong.MAX_VALUE else now + duration
}

private fun scanSelectDescriptors(
    process: Process,
    nfds: Int,
    read: ByteArray,
    write: ByteArray,
    except: ByteArray,
): Int {
    var ready = 0
    for (fd in 0 until nfds) {
        val requested = (if (read.isSet(fd)) PollEvents.NORMAL_INPUT else 0) or
            (if (write.isSet(fd)) PollEvents.NORMAL_OUTPUT else 0) or
            (if (except.isSet(fd)) PollEvents.POLLPRI else 0)
        if (requested == 0) continue
        val file = process.fdTable.acquire(fd) ?: return -Errno.EBADF
        val events = try { file.poll(requested).toInt() } finally { file.release() }
        if (events < 0) return events
        val readable = events and PollEvents.NORMAL_INPUT != 0
        val writable = events and PollEvents.NORMAL_OUTPUT != 0
        val exceptional = events and PollEvents.POLLPRI != 0
        read.set(fd, readable)
        write.set(fd, writable)
        except.set(fd, exceptional)
        if (readable) ready++
        if (writable) ready++
        if (exceptional) ready++
    }
    return ready
}

private fun ByteArray.isSet(fd: Int): Boolean =
    this[fd / Byte.SIZE_BITS].toInt() and (1 shl (fd % Byte.SIZE_BITS)) != 0

private fun ByteArray.set(fd: Int, value: Boolean) {
    val index = fd / Byte.SIZE_BITS
    val mask = 1 shl (fd % Byte.SIZE_BITS)
    this[index] = if (value) (this[index].toInt() or mask).toByte()
    else (this[index].toInt() and mask.inv()).toByte()
}

private fun scanPollDescriptors(
    process: Process,
    descriptors: ByteArray,
    count: Int,
): Int {
    var ready = 0
    val input = LittleEndianBuffer(descriptors)
    repeat(count) { index ->
        val offset = index * POLL_FD_SIZE
        val fd = input.readU32(offset).toInt()
        val requested = input.readU16(offset + Int.SIZE_BYTES).toInt()
        val returned = when {
            fd < 0 -> 0
            else -> {
                val file = process.fdTable.acquire(fd)
                if (file == null) {
                    PollEvents.POLLNVAL
                } else {
                    try {
                        val result = file.poll(requested)
                        if (result < 0) {
                            PollEvents.POLLERR
                        } else {
                            result.toInt() and
                                (requested or PollEvents.UNCONDITIONALLY_REPORTED)
                        }
                    } finally {
                        file.release()
                    }
                }
            }
        }

        input.writeU16(offset + Int.SIZE_BYTES + Short.SIZE_BYTES, returned.toUShort())
        if (returned != 0) {
            ready++
        }
    }
    return ready
}

internal fun read(regs: PtraceRegisters, process: Process): Long =
    FileIo.read(regs, process)

internal fun write(regs: PtraceRegisters, process: Process): Long =
    FileIo.write(regs, process)

internal fun pread64(regs: PtraceRegisters, process: Process): Long =
    FileIo.pread64(regs, process)

internal fun pwrite64(regs: PtraceRegisters, process: Process): Long =
    FileIo.pwrite64(regs, process)

internal fun readv(regs: PtraceRegisters, process: Process): Long =
    FileIo.readv(regs, process)

internal fun writev(regs: PtraceRegisters, process: Process): Long =
    FileIo.writev(regs, process)

private object FileIo {
    fun read(regs: PtraceRegisters, process: Process): Long =
        scalar(Direction.READ, positioned = false, regs, process)

    fun write(regs: PtraceRegisters, process: Process): Long =
        scalar(Direction.WRITE, positioned = false, regs, process)

    fun pread64(regs: PtraceRegisters, process: Process): Long =
        scalar(Direction.READ, positioned = true, regs, process)

    fun pwrite64(regs: PtraceRegisters, process: Process): Long =
        scalar(Direction.WRITE, positioned = true, regs, process)

    fun readv(regs: PtraceRegisters, process: Process): Long =
        vector(Direction.READ, regs, process)

    fun writev(regs: PtraceRegisters, process: Process): Long =
        vector(Direction.WRITE, regs, process)

    private fun scalar(
        direction: Direction,
        positioned: Boolean,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val count = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT).toInt()
        val position = regs[PtraceRegisters.IDX_R10].takeIf { positioned }
        val buffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
        direction.transfer(file, buffer, count, position).raw
    }

    private fun vector(
        direction: Direction,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val vectorCount = regs[PtraceRegisters.IDX_RDX]
        if (vectorCount > MAX_IO_VECTORS.toULong()) return@withFile errno(Errno.EINVAL)
        val buffer = UserIoVector.fromUser(
            process,
            regs[PtraceRegisters.IDX_RSI],
            vectorCount.toInt(),
        ) ?: return@withFile errno(Errno.EFAULT)
        direction.transfer(file, buffer, buffer.size, null).raw
    }

    private inline fun withFile(
        regs: PtraceRegisters,
        process: Process,
        operation: (OpenFileDescription) -> Long,
    ): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            operation(file)
        } finally {
            file.release()
        }
    }

    private enum class Direction {
        READ,
        WRITE;

        fun transfer(
            file: OpenFileDescription,
            buffer: IoBuffer,
            count: Int,
            position: ULong?,
        ): IoResult = when (this) {
            READ -> if (position == null) {
                file.read(buffer, 0, count)
            } else {
                file.readAt(position, buffer, 0, count)
            }

            WRITE -> if (position == null) {
                file.write(buffer, 0, count)
            } else {
                file.writeAt(position, buffer, 0, count)
            }
        }
    }
}

private class UserIoVector private constructor(
    private val segments: Array<Segment>,
    val size: Int,
) : IoBuffer {
    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (prepare(offset, count, writable = false)) PreparedBufferSource(this) else null

    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (prepare(offset, count, writable = true)) PreparedBufferDestination(this) else null

    override fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int {
        if (destinationOffset < 0 || count < 0 || destinationOffset > destination.size - count) {
            return 0
        }
        return transfer(sourceOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyTo(
                segmentOffset,
                destination,
                destinationOffset + copied,
                chunk,
            )
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int {
        if (sourceOffset < 0 || count < 0 || sourceOffset > source.size - count) return 0
        return transfer(destinationOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyFrom(segmentOffset, source, sourceOffset + copied, chunk)
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: CPointer<UByteVar>,
        count: Int,
    ): Int = transfer(destinationOffset, count) { segment, segmentOffset, copied, chunk ->
        segment.memory.copyFrom(segmentOffset, requireNotNull(source + copied), chunk)
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int =
        transfer(destinationOffset, count) { segment, segmentOffset, _, chunk ->
            segment.memory.fill(segmentOffset, chunk, value)
        }

    private fun prepare(offset: Int, count: Int, writable: Boolean): Boolean =
        transfer(offset, count) { segment, segmentOffset, _, chunk ->
            val prepared = if (writable) {
                segment.memory.prepareWrite(segmentOffset, chunk)
            } else {
                segment.memory.prepareRead(segmentOffset, chunk)
            }
            if (prepared == null) 0 else chunk
        } == count

    private inline fun transfer(
        offset: Int,
        count: Int,
        operation: (Segment, Int, Int, Int) -> Int,
    ): Int {
        if (offset < 0 || count < 0 || offset > size - count || count == 0) return 0

        var segmentIndex = segmentIndex(offset)
        var segmentStart = if (segmentIndex == 0) 0 else segments[segmentIndex - 1].endOffset
        var copied = 0
        while (copied < count) {
            val segment = segments[segmentIndex]
            val segmentOffset = offset + copied - segmentStart
            val chunk = minOf(count - copied, segment.endOffset - segmentStart - segmentOffset)
            val current = operation(segment, segmentOffset, copied, chunk)
            if (current !in 1..chunk) break
            copied += current
            if (current < chunk) break
            segmentStart = segment.endOffset
            segmentIndex++
        }
        return copied
    }

    private fun segmentIndex(offset: Int): Int {
        var low = 0
        var high = segments.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offset < segments[middle].endOffset) high = middle else low = middle + 1
        }
        return low
    }

    private data class Segment(val memory: UserMemory, val endOffset: Int)

    companion object {
        fun fromUser(process: Process, address: ULong, count: Int): UserIoVector? {
            if (count == 0) return UserIoVector(emptyArray(), 0)
            val vectorBytes = UserMemory(process.addressSpace, address)
                .copyFromUser(count * IO_VECTOR_SIZE) ?: return null
            val input = LittleEndianBuffer(vectorBytes)
            val segments = ArrayList<Segment>(count)
            var size = 0
            repeat(count) { index ->
                val vectorOffset = index * IO_VECTOR_SIZE
                val available = MAX_RW_COUNT - size.toULong()
                val length = minOf(input.readU64(vectorOffset + ULong.SIZE_BYTES), available)
                    .toInt()
                if (length != 0) {
                    size += length
                    segments += Segment(
                        UserMemory(process.addressSpace, input.readU64(vectorOffset)),
                        size,
                    )
                }
            }
            return UserIoVector(segments.toTypedArray(), size)
        }
    }
}

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

    val path = when (
        val result = FileSystemManager.vfs.absolutePath(
            context,
            context.workingDirectory,
        )
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }

    val result = ByteArray(path.size + 1)
    path.copyInto(result)

    if (length < result.size.toULong()) {
        return errno(Errno.ERANGE)
    }

    if (!UserMemory(
            process.addressSpace,
            userAddress,
        ).copyToUser(result)
    ) {
        return errno(Errno.EFAULT)
    }

    return result.size.toLong()
}
