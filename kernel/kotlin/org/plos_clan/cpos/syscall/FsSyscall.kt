@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.CreateDisposition
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.MountFlags
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.SeekOrigin
import org.plos_clan.cpos.fs.SymlinkBackend
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsPath
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.syscall.Syscall.userMemory
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
private const val O_CLOEXEC = 0x0008_0000uL
private const val O_NONBLOCK = 0x0000_0800uL
private const val POLL_FD_SIZE = 8
private const val MAX_POLL_FDS = 1024
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000uL

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
private const val STATFS_SIZE = 120
private const val STAT_BLOCK_SIZE = 512uL
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

private const val ST_RDONLY = 0x1uL
private const val ST_NOSUID = 0x2uL
private const val ST_NODEV = 0x4uL
private const val ST_NOEXEC = 0x8uL

private class LinuxStat(private val inode: Inode) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(STAT_SIZE).also { buffer ->
        val metadata = inode.metadata()
        LittleEndianBuffer(buffer).apply {
            writeU64(0, 0uL) // st_dev
            writeU64(8, inode.id.value)
            writeU64(16, metadata.linkCount.toULong())
            writeU32(24, metadata.mode.bits or typeBits(inode.type))
            writeU32(28, metadata.uid)
            writeU32(32, metadata.gid)
            writeU32(36, 0u) // __pad0
            writeU64(40, metadata.deviceNumber)
            writeU64(48, metadata.size)
            writeU64(56, STAT_BLKSIZE)
            writeU64(64, blocksFor(metadata.size))
        }
    }

    private fun typeBits(type: InodeType): UInt = when (type) {
        InodeType.REGULAR -> S_IFREG
        InodeType.DIRECTORY -> S_IFDIR
        InodeType.SYMLINK -> S_IFLNK
        InodeType.CHARACTER_DEVICE -> S_IFCHR
        InodeType.BLOCK_DEVICE -> S_IFBLK
        InodeType.PIPE -> S_IFIFO
        InodeType.SOCKET -> S_IFSOCK
    }

    private fun blocksFor(size: ULong): ULong =
        if (size > ULong.MAX_VALUE - (STAT_BLOCK_SIZE - 1uL)) {
            ULong.MAX_VALUE
        } else {
            (size + STAT_BLOCK_SIZE - 1uL) / STAT_BLOCK_SIZE
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

fun sysOpen(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    return open(
        process = process,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RSI],
        rawMode = regs[PtraceRegisters.IDX_RDX],
    )
}

fun sysOpenAt(regs: PtraceRegisters, process: Process): Long {
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

fun sysClose(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    return if (process.fdTable.close(fd)) 0L else errno(Errno.EBADF)
}

fun sysPipe2(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_RSI]
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
    if (!UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI]).copyToUser(output)) {
        process.fdTable.close(readFd)
        process.fdTable.close(writeFd)
        return errno(Errno.EFAULT)
    }
    return 0L
}

fun sysChown(regs: PtraceRegisters, process: Process): Long = chownPath(
    process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    uid = regs[PtraceRegisters.IDX_RSI],
    gid = regs[PtraceRegisters.IDX_RDX],
    followFinalSymlink = true,
)

fun sysLchown(regs: PtraceRegisters, process: Process): Long = chownPath(
    process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    uid = regs[PtraceRegisters.IDX_RSI],
    gid = regs[PtraceRegisters.IDX_RDX],
    followFinalSymlink = false,
)

fun sysFchown(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        changeOwner(process, file.path, regs[PtraceRegisters.IDX_RSI], regs[PtraceRegisters.IDX_RDX])
    } finally {
        file.release()
    }
}

fun sysFchownat(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R8]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH).toULong()
    if (flags > UInt.MAX_VALUE.toULong() || flags and supportedFlags.inv() != 0uL) {
        return errno(Errno.EINVAL)
    }
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val target = if (pathname.isEmpty() && flags.toInt() and AT_EMPTY_PATH != 0) {
        if (dirFd == AT_FDCWD) {
            process.context?.workingDirectory ?: return errno(Errno.ENOENT)
        } else {
            if (dirFd < 0) return errno(Errno.EBADF)
            val file = process.fdTable.acquire(dirFd) ?: return errno(Errno.EBADF)
            try {
                file.path
            } finally {
                file.release()
            }
        }
    } else {
        if (pathname.isEmpty()) return errno(Errno.ENOENT)
        when (val result = resolveAt(
            process,
            dirFd,
            VfsPathname.fromBytes(pathname),
            flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
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

fun sysStat(regs: PtraceRegisters, process: Process): Long = statPath(
    process = process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    statAddress = regs[PtraceRegisters.IDX_RSI],
    followFinalSymlink = true,
)

fun sysLstat(regs: PtraceRegisters, process: Process): Long = statPath(
    process = process,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    statAddress = regs[PtraceRegisters.IDX_RSI],
    followFinalSymlink = false,
)

fun sysStatfs(regs: PtraceRegisters, process: Process): Long {
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

fun sysFstatfs(regs: PtraceRegisters, process: Process): Long {
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

fun sysAccess(regs: PtraceRegisters, process: Process): Long {
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

fun sysFaccessat(regs: PtraceRegisters, process: Process): Long = accessAt(
    process = process,
    dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt(),
    pathnameAddress = regs[PtraceRegisters.IDX_RSI],
    mode = regs[PtraceRegisters.IDX_RDX],
    flags = 0uL,
)

fun sysFaccessat2(regs: PtraceRegisters, process: Process): Long = accessAt(
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
    val context = process.context ?: return errno(Errno.ENOENT)
    val emptyPath = pathname.isEmpty()
    val target = if (emptyPath && flags.toInt() and AT_EMPTY_PATH != 0) {
        if (dirFd == AT_FDCWD) context.workingDirectory else {
            if (dirFd < 0) return errno(Errno.EBADF)
            val file = process.fdTable.acquire(dirFd) ?: return errno(Errno.EBADF)
            try {
                file.path
            } finally {
                file.release()
            }
        }
    } else {
        if (emptyPath) return errno(Errno.ENOENT)
        when (val result = resolveAt(
            process,
            dirFd,
            VfsPathname.fromBytes(pathname),
            flags.toInt() and AT_SYMLINK_NOFOLLOW == 0,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    val permissions = inode.metadata().mode.bits
    return if (mode and 0x1uL != 0uL && permissions and 0x49u == 0u) {
        errno(Errno.EACCES)
    } else {
        0L
    }
}

fun sysNewfstatat(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_R10]
    val supportedFlags = (AT_SYMLINK_NOFOLLOW or AT_NO_AUTOMOUNT or AT_EMPTY_PATH).toULong()
    if (flags and supportedFlags.inv() != 0uL || flags > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }

    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
    val follow = flags.toInt() and AT_SYMLINK_NOFOLLOW == 0
    val context = process.context ?: return errno(Errno.ENOENT)
    val target = if (pathname.isEmpty() && flags.toInt() and AT_EMPTY_PATH != 0) {
        if (dirFd == AT_FDCWD) {
            context.workingDirectory
        } else {
            if (dirFd < 0) return errno(Errno.EBADF)
            val file = process.fdTable.acquire(dirFd) ?: return errno(Errno.EBADF)
            try {
                file.path
            } finally {
                file.release()
            }
        }
    } else {
        when (val result = resolveAt(process, dirFd, VfsPathname.fromBytes(pathname), follow)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val inode = target.inode ?: return errno(Errno.ENOENT)
    return if (UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDX]).copyToUser(
            LinuxStat(inode).toNativeBytes(),
        )
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

fun sysReadlink(regs: PtraceRegisters, process: Process): Long = readlinkAt(
    process = process,
    dirFd = AT_FDCWD,
    pathnameAddress = regs[PtraceRegisters.IDX_RDI],
    bufferAddress = regs[PtraceRegisters.IDX_RSI],
    bufferSize = regs[PtraceRegisters.IDX_RDX],
)

fun sysReadlinkat(regs: PtraceRegisters, process: Process): Long = readlinkAt(
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
    val path = if (pathname.isEmpty()) {
        if (dirFd == AT_FDCWD) return errno(Errno.ENOENT)
        if (dirFd < 0) return errno(Errno.EBADF)
        val file = process.fdTable.acquire(dirFd) ?: return errno(Errno.EBADF)
        try {
            file.path
        } finally {
            file.release()
        }
    } else {
        when (val result = resolveAt(
            process,
            dirFd,
            VfsPathname.fromBytes(pathname),
            followFinalSymlink = false,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
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
): VfsResult<VfsPath> {
    val context = process.context ?: return VfsResult.Err(VfsError.NOT_FOUND)
    if (pathname.isAbsolute || dirFd == AT_FDCWD) {
        return FileSystemManager.vfs.resolve(context, pathname, followFinalSymlink)
    }
    if (dirFd < 0) {
        return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
    }
    val directory = process.fdTable.acquire(dirFd)
        ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
    return try {
        if (directory.inode.type != InodeType.DIRECTORY) {
            VfsResult.Err(VfsError.NOT_DIRECTORY)
        } else {
            FileSystemManager.vfs.resolveAt(
                context = context,
                directory = directory.path,
                pathname = pathname,
                followFinalSymlink = followFinalSymlink,
            )
        }
    } finally {
        directory.release()
    }
}

fun sysFstat(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        val data = LinuxStat(file.inode).toNativeBytes()
        if (UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI]).copyToUser(data)) {
            0L
        } else {
            errno(Errno.EFAULT)
        }
    } finally {
        file.release()
    }
}

fun sysGetdents64(regs: PtraceRegisters, process: Process): Long {
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
    val context = process.context
        ?: return errno(Errno.ENOENT)
    val path = when (
        val result = FileSystemManager.vfs.resolve(
            context = context,
            pathname = VfsPathname.fromBytes(pathname),
            followFinalSymlink = followFinalSymlink,
        )
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val inode = path.inode ?: return errno(Errno.ENOENT)
    return if (UserMemory(process.addressSpace, statAddress).copyToUser(LinuxStat(inode).toNativeBytes())) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

fun sysLseek(regs: PtraceRegisters, process: Process): Long {
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

fun sysDup(regs: PtraceRegisters, process: Process): Long {
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

fun sysDup2(regs: PtraceRegisters, process: Process): Long {
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

fun sysFcntl(regs: PtraceRegisters, process: Process): Long {
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

fun sysPoll(regs: PtraceRegisters, process: Process): Long {
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

fun sysPselect6(regs: PtraceRegisters, process: Process): Long {
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

fun sysRead(regs: PtraceRegisters, process: Process): Long {
    val file = process.fdTable.acquire(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    try {
        val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
        if (requested == 0uL) {
            return 0L
        }

        val userAddress = regs[PtraceRegisters.IDX_RSI]
        val buffer = UserMemory(process.addressSpace, userAddress)
        val count = requested.toInt()
        val destination = buffer.prepareWrite(0, count)
            ?: return errno(Errno.EFAULT)

        val result = file.read(destination, 0, count)
        return result.raw
    } finally {
        file.release()
    }
}

fun sysWrite(regs: PtraceRegisters, process: Process): Long {
    val file = process.fdTable.acquire(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    try {
        val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
        if (requested == 0uL) {
            return 0L
        }

        val userAddress = regs[PtraceRegisters.IDX_RSI]
        val buffer = UserMemory(process.addressSpace, userAddress)
        val count = requested.toInt()
        val result = if (file.inode.type == InodeType.PIPE) {
            val source = buffer.prepareRead(0, count)
                ?: return errno(Errno.EFAULT)
            file.write(source, 0, count)
        } else {
            file.write(buffer, 0, count)
        }
        return result.raw
    } finally {
        file.release()
    }
}

fun sysReadv(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val vectorCountValue = regs[PtraceRegisters.IDX_RDX]
        if (vectorCountValue > MAX_IO_VECTORS.toULong()) {
            return errno(Errno.EINVAL)
        }

        val vectorCount = vectorCountValue.toInt()
        if (vectorCount == 0) return 0L
        val vectors = UserMemory(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
        ).copyFromUser(vectorCount * IO_VECTOR_SIZE)
            ?: return errno(Errno.EFAULT)
        val cursor = IoVectorCursor(process, vectors, vectorCount)
        val requested = cursor.remaining
        if (requested == 0uL) {
            return 0L
        }
        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        val transferBuffer = ByteArrayBuffer(buffer)
        var transferred = 0uL
        while (cursor.remaining != 0uL) {
            val count = minOf(cursor.remaining, buffer.size.toULong()).toInt()
            if (!cursor.isWritable(count)) {
                return partialOrError(transferred, Errno.EFAULT)
            }

            val result = file.read(transferBuffer, 0, count)
            if (!result.isSuccess) {
                return if (transferred != 0uL) transferred.toLong() else result.raw
            }
            val current = result.bytesTransferred
            if (current == 0) return transferred.toLong()
            if (!cursor.copyToUser(buffer, current)) {
                return partialOrError(transferred, Errno.EFAULT)
            }

            transferred += current.toULong()
            if (current < count) return transferred.toLong()
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

fun sysWritev(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val vectorCountValue = regs[PtraceRegisters.IDX_RDX]
        if (vectorCountValue > MAX_IO_VECTORS.toULong()) {
            return errno(Errno.EINVAL)
        }

        val vectorCount = vectorCountValue.toInt()
        if (vectorCount == 0) return 0L
        val vectors = UserMemory(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
        ).copyFromUser(vectorCount * IO_VECTOR_SIZE)
            ?: return errno(Errno.EFAULT)
        val cursor = IoVectorCursor(process, vectors, vectorCount)
        val requested = cursor.remaining
        if (requested == 0uL) {
            return 0L
        }
        file.discardWrite(requested.toInt())?.let { return it.raw }

        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        val transferBuffer = ByteArrayBuffer(buffer)
        var transferred = 0uL
        while (cursor.remaining != 0uL) {
            val count = minOf(cursor.remaining, buffer.size.toULong()).toInt()
            if (!cursor.copyFromUser(buffer, count)) {
                return partialOrError(transferred, Errno.EFAULT)
            }

            val result = file.write(transferBuffer, 0, count)
            if (!result.isSuccess) {
                return if (transferred != 0uL) transferred.toLong() else result.raw
            }
            val current = result.bytesTransferred
            if (current == 0) return transferred.toLong()
            transferred += current.toULong()
            if (current < count) return transferred.toLong()
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

private class IoVectorCursor(
    private val process: Process,
    private val vectors: ByteArray,
    private val vectorCount: Int,
) {
    private val input = LittleEndianBuffer(vectors)

    var remaining = totalLength()
        private set

    private var index = 0
    private var offset = 0uL

    fun isWritable(count: Int): Boolean = visit(count, false) { user, _, size ->
        user.isWritable(size)
    }

    fun copyFromUser(destination: ByteArray, count: Int): Boolean =
        visit(count, true) { user, bufferOffset, size ->
            user.copyFromUser(destination, bufferOffset, size)
        }

    fun copyToUser(source: ByteArray, count: Int): Boolean =
        visit(count, true) { user, bufferOffset, size ->
            user.copyToUser(source, bufferOffset, size)
        }

    private inline fun visit(
        count: Int,
        advance: Boolean,
        operation: (UserMemory, Int, Int) -> Boolean,
    ): Boolean {
        if (count < 0 || count.toULong() > remaining) return false

        var currentIndex = index
        var currentOffset = offset
        var processed = 0
        while (processed < count) {
            if (currentIndex >= vectorCount) return false
            val vectorOffset = currentIndex * IO_VECTOR_SIZE
            val vectorLength = input.readU64(vectorOffset + ULong.SIZE_BYTES)
            if (currentOffset >= vectorLength) {
                currentIndex++
                currentOffset = 0uL
                continue
            }

            val size = minOf(
                (count - processed).toULong(),
                vectorLength - currentOffset,
            ).toInt()
            val user = userMemory(
                process,
                input.readU64(vectorOffset),
                currentOffset,
            ) ?: return false
            if (!operation(user, processed, size)) return false
            processed += size
            currentOffset += size.toULong()
        }

        if (advance) {
            index = currentIndex
            offset = currentOffset
            remaining -= count.toULong()
        }
        return true
    }

    private fun totalLength(): ULong {
        var total = 0uL
        repeat(vectorCount) { current ->
            val length = input.readU64(current * IO_VECTOR_SIZE + ULong.SIZE_BYTES)
            total += minOf(length, MAX_RW_COUNT - total)
        }
        return total
    }
}

fun sysIoctl(regs: PtraceRegisters, process: Process): Long {
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

fun sysChdir(regs: PtraceRegisters, process: Process): Long {
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

fun sysFchdir(regs: PtraceRegisters, process: Process): Long {
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

fun sysGetCWD(regs: PtraceRegisters, process: Process): Long {
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
