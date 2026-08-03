@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.CreateDisposition
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.VMA_EXEC
import org.plos_clan.cpos.mem.VMA_READ
import org.plos_clan.cpos.mem.VMA_WRITE
import org.plos_clan.cpos.mem.VmaMapRequest
import org.plos_clan.cpos.mem.VmaResult
import org.plos_clan.cpos.mem.VmaType
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.experimental.ExperimentalNativeApi

private const val MSR_EFER = 0xC0000080U // EFER MSR寄存器
private const val MSR_STAR = 0xC0000081U // STAR MSR寄存器
private const val MSR_LSTAR = 0xC0000082U // LSTAR MSR寄存器
private const val MSR_SYSCALL_MASK = 0xC0000084U
private const val EFER_SYSCALL_ENABLE = 1uL
private const val SYSCALL_RFLAGS_MASK = 0x47700uL
private const val KERNEL_CODE_SELECTOR = 0x08uL
private const val USER_DATA_SELECTOR = 0x1buL

private const val MAX_SYSCALLS = 512
private const val PATH_MAX = 4096
private const val IO_CHUNK_SIZE = 64 * 1024
private const val MAX_RW_COUNT = 0x7ffff000uL
private const val AT_FDCWD = -100L

private const val SYS_READ = 0
private const val SYS_WRITE = 1
private const val SYS_OPEN = 2
private const val SYS_CLOSE = 3
private const val SYS_MMAP = 9
private const val SYS_MPROTECT = 10
private const val SYS_MUNMAP = 11
private const val SYS_IOCTL = 16
private const val SYS_ARCH_PRCTL = 158
private const val SYS_OPENAT = 257

private const val ARCH_SET_GS = 0x1001uL
private const val ARCH_SET_FS = 0x1002uL
private const val ARCH_GET_FS = 0x1003uL
private const val ARCH_GET_GS = 0x1004uL
private const val MSR_KERNEL_GS_BASE = 0xC0000102U

private const val PROT_READ = 0x1uL
private const val PROT_WRITE = 0x2uL
private const val PROT_EXEC = 0x4uL
private const val SUPPORTED_PROT = 0x7uL

private const val MAP_SHARED = 0x01uL
private const val MAP_PRIVATE = 0x02uL
private const val MAP_SHARED_VALIDATE = 0x03uL
private const val MAP_TYPE = 0x0fuL
private const val MAP_FIXED = 0x10uL
private const val MAP_ANONYMOUS = 0x20uL
private const val MAP_GROWSDOWN = 0x0100uL
private const val MAP_DENYWRITE = 0x0800uL
private const val MAP_EXECUTABLE = 0x1000uL
private const val MAP_LOCKED = 0x2000uL
private const val MAP_NORESERVE = 0x4000uL
private const val MAP_POPULATE = 0x8000uL
private const val MAP_NONBLOCK = 0x10000uL
private const val MAP_STACK = 0x20000uL
private const val MAP_HUGETLB = 0x40000uL
private const val MAP_SYNC = 0x80000uL
private const val MAP_FIXED_NOREPLACE = 0x100000uL
private const val SUPPORTED_MMAP_FLAGS = 0x001f_f93fuL

private const val ESRCH = 3
private const val EPERM = 1
private const val EBADF = 9
private const val EACCES = 13
private const val EFAULT = 14
private const val EISDIR = 21
private const val EINVAL = 22
private const val EMFILE = 24
private const val ENOSYS = 38
private const val ENOTSUP = 95

private const val SUPPORTED_OPEN_FLAGS =
    OpenFlags.O_ACCMODE or OpenFlags.O_CREAT or OpenFlags.O_EXCL or
        OpenFlags.O_NOCTTY or OpenFlags.O_TRUNC or OpenFlags.O_APPEND or
        OpenFlags.O_NONBLOCK or OpenFlags.O_DSYNC or OpenFlags.O_SYNC or
        OpenFlags.O_ASYNC or OpenFlags.O_DIRECT or OpenFlags.O_LARGEFILE or
        OpenFlags.O_DIRECTORY or OpenFlags.O_NOFOLLOW or OpenFlags.O_NOATIME or
        OpenFlags.O_CLOEXEC or OpenFlags.O_PATH or OpenFlags.O_TMPFILE

private typealias SyscallHandler = (PtraceRegisters, Process) -> Long

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("syscall_handler")
fun syscallHandler(frame: COpaquePointer?) {
    Syscall.syscallHandle(PtraceRegisters(requireNotNull(frame).reinterpret()))
}

object Syscall {
    private val handlers = arrayOfNulls<SyscallHandler>(MAX_SYSCALLS).apply {
        this[SYS_READ] = ::sysRead
        this[SYS_WRITE] = ::sysWrite
        this[SYS_OPEN] = ::sysOpen
        this[SYS_CLOSE] = ::sysClose
        this[SYS_MMAP] = ::sysMmap
        this[SYS_MPROTECT] = ::sysMprotect
        this[SYS_MUNMAP] = ::sysMunmap
        this[SYS_IOCTL] = ::sysIoctl
        this[SYS_ARCH_PRCTL] = ::sysArchPrctl

        // mlibc implements open() through openat(AT_FDCWD, ...).
        this[SYS_OPENAT] = ::sysOpenAt
    }

    fun syscallHandle(regs: PtraceRegisters) {
        val number = regs[PtraceRegisters.IDX_RAX]
        val handler = if (number < handlers.size.toULong()) {
            handlers[number.toInt()]
        } else {
            null
        }
        val result = when {
            handler == null -> errno(ENOSYS)
            else -> ProcessManager.currentProcess()?.let { handler(regs, it) }
                ?: errno(ESRCH)
        }
        regs[PtraceRegisters.IDX_RAX] = result.toULong()
    }

    private fun sysOpen(regs: PtraceRegisters, process: Process): Long {
        val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
            ?: return errno(EFAULT)
        return open(
            process = process,
            pathname = pathname,
            rawFlags = regs[PtraceRegisters.IDX_RSI],
            rawMode = regs[PtraceRegisters.IDX_RDX],
        )
    }

    private fun sysOpenAt(regs: PtraceRegisters, process: Process): Long {
        val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
            ?: return errno(EFAULT)
        val dirFd = regs[PtraceRegisters.IDX_RDI].toLong()
        if (pathname.firstOrNull() != '/'.code.toByte() && dirFd != AT_FDCWD) {
            return errno(ENOTSUP)
        }
        return open(
            process = process,
            pathname = pathname,
            rawFlags = regs[PtraceRegisters.IDX_RDX],
            rawMode = regs[PtraceRegisters.IDX_R10],
        )
    }

    private fun open(
        process: Process,
        pathname: ByteArray,
        rawFlags: ULong,
        rawMode: ULong,
    ): Long {
        if (rawFlags > UInt.MAX_VALUE.toULong()) {
            return errno(EINVAL)
        }
        val flags = rawFlags.toInt()
        if (flags and SUPPORTED_OPEN_FLAGS.inv() != 0) {
            return errno(EINVAL)
        }
        if (flags and OpenFlags.O_PATH != 0 ||
            flags and OpenFlags.O_TMPFILE == OpenFlags.O_TMPFILE
        ) {
            return errno(ENOTSUP)
        }

        val access = when (flags and OpenFlags.O_ACCMODE) {
            OpenFlags.O_RDONLY -> AccessMode.READ
            OpenFlags.O_WRONLY -> AccessMode.WRITE
            OpenFlags.O_RDWR -> AccessMode.READ_WRITE
            else -> return errno(EINVAL)
        }
        val create = when {
            flags and OpenFlags.O_CREAT == 0 -> CreateDisposition.OPEN_EXISTING
            flags and OpenFlags.O_EXCL != 0 -> CreateDisposition.CREATE_NEW
            else -> CreateDisposition.OPEN_OR_CREATE
        }
        val context = FileSystemManager.kernelContext
            ?: return errno(VfsError.NOT_FOUND.errno)
        val opened = FileSystemManager.vfs.open(
            context = context,
            pathname = VfsPathname.fromBytes(pathname),
            options = OpenOptions(
                access = access,
                create = create,
                createMode = FileMode(rawMode.toUInt() and 0x1ffu),
                truncate = flags and OpenFlags.O_TRUNC != 0,
                append = flags and OpenFlags.O_APPEND != 0,
                directoryOnly = flags and OpenFlags.O_DIRECTORY != 0,
                followFinalSymlink = flags and OpenFlags.O_NOFOLLOW == 0,
            ),
        )
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
            errno(EMFILE)
        }
    }

    private fun sysClose(regs: PtraceRegisters, process: Process): Long {
        val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(EBADF)
        return if (process.fdTable.close(fd)) 0L else errno(EBADF)
    }

    private fun sysMmap(regs: PtraceRegisters, process: Process): Long {
        val hint = regs[PtraceRegisters.IDX_RDI]
        val length = regs[PtraceRegisters.IDX_RSI]
        val protection = regs[PtraceRegisters.IDX_RDX]
        val flags = regs[PtraceRegisters.IDX_R10]
        val fdValue = regs[PtraceRegisters.IDX_R8]
        val offset = regs[PtraceRegisters.IDX_R9]
        val mapType = flags and MAP_TYPE
        val anonymous = (flags and MAP_ANONYMOUS) != 0uL

        if (length == 0uL || (protection and SUPPORTED_PROT.inv()) != 0uL) {
            return errno(EINVAL)
        }
        if (mapType != MAP_PRIVATE &&
            mapType != MAP_SHARED &&
            mapType != MAP_SHARED_VALIDATE
        ) {
            return errno(EINVAL)
        }
        val unknownFlags = flags and SUPPORTED_MMAP_FLAGS.inv()
        if (unknownFlags != 0uL && mapType == MAP_SHARED_VALIDATE) {
            return errno(ENOTSUP)
        }
        if ((flags and MAP_SYNC) != 0uL && mapType != MAP_SHARED_VALIDATE) {
            return errno(EINVAL)
        }
        if ((flags and MAP_HUGETLB) != 0uL || (flags and MAP_SYNC) != 0uL) {
            return errno(ENOTSUP)
        }
        if ((offset and (PAGE_SIZE_BYTES - 1uL)) != 0uL ||
            anonymous && offset != 0uL
        ) {
            return errno(EINVAL)
        }

        val shared = mapType != MAP_PRIVATE
        val fixed = (flags and (MAP_FIXED or MAP_FIXED_NOREPLACE)) != 0uL
        val noReplace = (flags and MAP_FIXED_NOREPLACE) != 0uL
        val access = protection and SUPPORTED_PROT
        if (fixed && (hint and (PAGE_SIZE_BYTES - 1uL)) != 0uL) {
            return errno(EINVAL)
        }

        if (anonymous) {
            return mmapResult(
                process.vma.map(
                    VmaMapRequest(
                        hint = hint,
                        length = length,
                        access = access,
                        fixed = fixed,
                        noReplace = noReplace,
                        shared = shared,
                        type = VmaType.ANONYMOUS,
                    ),
                ),
            )
        }

        val fd = fileDescriptor(fdValue) ?: return errno(EBADF)
        val file = process.fdTable.acquire(fd) ?: return errno(EBADF)
        try {
            if (file.inode.type == InodeType.DIRECTORY) {
                return errno(EISDIR)
            }
            if (!file.access.canRead) {
                return errno(EACCES)
            }
            if (shared && (protection and PROT_WRITE) != 0uL && !file.access.canWrite) {
                return errno(EACCES)
            }
            /* Writable shared mappings need page-cache writeback, which this
             * VFS does not expose yet. Do not silently implement private-copy
             * semantics for them. */
            if (shared && (protection and PROT_WRITE) != 0uL) {
                return errno(ENOTSUP)
            }

            return mmapResult(
                process.vma.map(
                    VmaMapRequest(
                        hint = hint,
                        length = length,
                        access = access,
                        fixed = fixed,
                        noReplace = noReplace,
                        shared = shared,
                        type = VmaType.FILE,
                        offset = offset,
                        pageReader = { pageOffset, destination ->
                            val result = file.readAt(pageOffset, destination)
                            if (result.isSuccess) result.bytesTransferred else result.raw.toInt()
                        },
                    ),
                ),
            )
        } finally {
            file.release()
        }
    }

    private fun sysMunmap(regs: PtraceRegisters, process: Process): Long =
        vmaStatus(
            process.vma.unmap(
                address = regs[PtraceRegisters.IDX_RDI],
                length = regs[PtraceRegisters.IDX_RSI],
            ),
        )

    private fun sysMprotect(regs: PtraceRegisters, process: Process): Long {
        val protection = regs[PtraceRegisters.IDX_RDX]
        if ((protection and SUPPORTED_PROT.inv()) != 0uL) {
            return errno(EINVAL)
        }
        return vmaStatus(
            process.vma.protect(
                address = regs[PtraceRegisters.IDX_RDI],
                length = regs[PtraceRegisters.IDX_RSI],
                access = protection and (VMA_READ or VMA_WRITE or VMA_EXEC),
            ),
        )
    }

    private fun mmapResult(result: VmaResult<ULong>): Long = when (result) {
        is VmaResult.Ok -> result.value.toLong()
        is VmaResult.Err -> errno(result.errno)
    }

    private fun vmaStatus(result: VmaResult<Unit>): Long = when (result) {
        is VmaResult.Ok -> 0L
        is VmaResult.Err -> errno(result.errno)
    }

    private fun sysRead(regs: PtraceRegisters, process: Process): Long {
        val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(EBADF)
        val file = process.fdTable.acquire(fd) ?: return errno(EBADF)
        try {
            val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
            if (requested == 0uL) {
                return 0L
            }

            val userAddress = regs[PtraceRegisters.IDX_RSI]
            val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
            var transferred = 0uL
            while (transferred < requested) {
                val count = minOf(
                    requested - transferred,
                    buffer.size.toULong(),
                ).toInt()
                val user = userMemory(process, userAddress, transferred)
                    ?: return partialOrError(transferred, EFAULT)
                if (!user.isWritable(count)) {
                    return partialOrError(transferred, EFAULT)
                }

                val result = file.read(buffer, count = count)
                if (!result.isSuccess) {
                    return if (transferred != 0uL) transferred.toLong() else result.raw
                }
                val current = result.bytesTransferred
                if (current == 0) {
                    break
                }
                if (!user.copyToUser(buffer, size = current)) {
                    return partialOrError(transferred, EFAULT)
                }
                transferred += current.toULong()
                if (current < count) {
                    break
                }
            }
            return transferred.toLong()
        } finally {
            file.release()
        }
    }

    private fun sysWrite(regs: PtraceRegisters, process: Process): Long {
        val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(EBADF)
        val file = process.fdTable.acquire(fd) ?: return errno(EBADF)
        try {
            val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
            if (requested == 0uL) {
                return 0L
            }

            val userAddress = regs[PtraceRegisters.IDX_RSI]
            val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
            var transferred = 0uL
            while (transferred < requested) {
                val count = minOf(
                    requested - transferred,
                    buffer.size.toULong(),
                ).toInt()
                val user = userMemory(process, userAddress, transferred)
                    ?: return partialOrError(transferred, EFAULT)
                if (!user.copyFromUser(buffer, size = count)) {
                    return partialOrError(transferred, EFAULT)
                }

                val result = file.write(buffer, count = count)
                if (!result.isSuccess) {
                    return if (transferred != 0uL) transferred.toLong() else result.raw
                }
                val current = result.bytesTransferred
                if (current == 0) {
                    break
                }
                transferred += current.toULong()
                if (current < count) {
                    break
                }
            }
            return transferred.toLong()
        } finally {
            file.release()
        }
    }

    private fun sysIoctl(regs: PtraceRegisters, process: Process): Long {
        val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(EBADF)
        val file = process.fdTable.acquire(fd) ?: return errno(EBADF)
        return try {
            file.ioctl(
                command = regs[PtraceRegisters.IDX_RSI].toInt(),
                args = UserMemory(
                    process.vma.pageDirectory,
                    regs[PtraceRegisters.IDX_RDX],
                ),
            )
        } finally {
            file.release()
        }
    }

    private fun sysArchPrctl(regs: PtraceRegisters, process: Process): Long {
        val command = regs[PtraceRegisters.IDX_RDI]
        val argument = regs[PtraceRegisters.IDX_RSI]
        return when (command) {
            ARCH_SET_FS -> {
                if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                    errno(EPERM)
                } else {
                    regs[PtraceRegisters.IDX_FS_BASE] = argument
                    0L
                }
            }

            ARCH_SET_GS -> {
                if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                    errno(EPERM)
                } else {
                    /* Kernel mode is running after SWAPGS, so the pending
                     * userspace GS value lives in IA32_KERNEL_GS_BASE. */
                    bridge.wrmsr(MSR_KERNEL_GS_BASE, argument)
                    0L
                }
            }

            ARCH_GET_FS -> copyWordToUser(
                process,
                argument,
                regs[PtraceRegisters.IDX_FS_BASE],
            )

            ARCH_GET_GS -> copyWordToUser(
                process,
                argument,
                bridge.rdmsr(MSR_KERNEL_GS_BASE),
            )

            else -> errno(EINVAL)
        }
    }

    private fun copyWordToUser(process: Process, address: ULong, value: ULong): Long {
        val bytes = ByteArray(ULong.SIZE_BYTES) { index ->
            (value shr (index * Byte.SIZE_BITS)).toByte()
        }
        return if (UserMemory(process.vma.pageDirectory, address).copyToUser(bytes)) {
            0L
        } else {
            errno(EFAULT)
        }
    }

    private fun copyPath(process: Process, address: ULong): ByteArray? =
        UserMemory(process.vma.pageDirectory, address).copyCStringFromUser(PATH_MAX)

    private fun userMemory(process: Process, base: ULong, offset: ULong): UserMemory? {
        if (offset > ULong.MAX_VALUE - base) {
            return null
        }
        return UserMemory(process.vma.pageDirectory, base + offset)
    }

    private fun fileDescriptor(value: ULong): Int? =
        value.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()

    private fun partialOrError(transferred: ULong, error: Int): Long =
        if (transferred == 0uL) errno(error) else transferred.toLong()

    private fun errno(value: Int): Long = -value.toLong()

    fun initialize(lapicId: ULong, isBsp: Boolean) {
        val syscallUserBase = USER_DATA_SELECTOR - 8uL
        val star = (syscallUserBase shl 48) or (KERNEL_CODE_SELECTOR shl 32)

        bridge.setup_syscall_cpu(lapicId, if (isBsp) 1u else 0u)
        bridge.wrmsr(MSR_EFER, bridge.rdmsr(MSR_EFER) or EFER_SYSCALL_ENABLE)
        bridge.wrmsr(MSR_STAR, star)
        bridge.wrmsr(MSR_LSTAR, bridge.get_asm_syscall_handle_address())
        bridge.wrmsr(MSR_SYSCALL_MASK, SYSCALL_RFLAGS_MASK)
    }
}
