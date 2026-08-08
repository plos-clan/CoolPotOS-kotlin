@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
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

private const val SYS_READ = 0
private const val SYS_WRITE = 1
private const val SYS_OPEN = 2
private const val SYS_CLOSE = 3
private const val SYS_WAIT4 = 61
private const val SYS_STAT = 4
private const val SYS_FSTAT = 5
private const val SYS_LSTAT = 6
private const val SYS_POLL = 7
private const val SYS_LSEEK = 8
private const val SYS_PIPE = 22
private const val SYS_GETDENTS64 = 217
private const val SYS_CLONE = 56
private const val SYS_EXECVE = 59
private const val SYS_PIPE2 = 293
private const val SYS_DUP = 32
private const val SYS_DUP2 = 33
private const val SYS_MMAP = 9
private const val SYS_MPROTECT = 10
private const val SYS_MUNMAP = 11
private const val SYS_IOCTL = 16
private const val SYS_READV = 19
private const val SYS_WRITEV = 20
private const val SYS_ACCESS = 21
private const val SYS_FCNTL = 72
private const val SYS_NANO_SLEEP = 35
private const val SYS_GETPID = 39
private const val SYS_EXIT = 60
private const val SYS_GETTIMEOFDAY = 96
private const val SYS_CHOWN = 92
private const val SYS_FCHOWN = 93
private const val SYS_LCHOWN = 94
private const val SYS_GETEUID = 107
private const val SYS_GETEGID = 108
private const val SYS_GETPPID = 110
private const val SYS_GETPGRP = 111
private const val SYS_SETPGID = 109
private const val SYS_SETFSUID = 122
private const val SYS_SETFSGID = 123
private const val SYS_GETRESUID = 118
private const val SYS_GETRESGID = 120
private const val SYS_UNAME = 63
private const val SYS_GETCWD = 79
private const val SYS_GETUID = 102
private const val SYS_GETGID = 104
private const val SYS_GETSID = 124
private const val SYS_ARCH_PRCTL = 158
private const val SYS_REBOOT = 169
private const val SYS_GETTID = 186
private const val SYS_SET_TID_ADDRESS = 218
private const val SYS_EXIT_GROUP = 231
private const val SYS_CLOCK_GETTIME = 228
private const val SYS_OPENAT = 257
private const val SYS_NEWFSTATAT = 262
private const val SYS_FCHOWNAT = 260
private const val SYS_SET_ROBUST_LIST = 273
private const val SYS_PRLIMIT64 = 302
private const val SYS_GETRANDOM = 318
private const val SYS_RSEQ = 334
private const val SYS_TIME = 201
private const val SYS_FACCESSAT = 269
private const val SYS_PSELECT6 = 270
private const val SYS_FACCESSAT2 = 439
private const val SYS_RT_SIGACTION = 13
private const val SYS_RT_SIGPROCMASK = 14

const val SUPPORTED_PROT = 0x7uL

const val SUPPORTED_OPEN_FLAGS =
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
        this[SYS_WAIT4] = ::sysWait4
        this[SYS_CLONE] = ::sysClone
        this[SYS_EXECVE] = ::sysExecve
        this[SYS_CHOWN] = ::sysChown
        this[SYS_FCHOWN] = ::sysFchown
        this[SYS_LCHOWN] = ::sysLchown
        this[SYS_STAT] = ::sysStat
        this[SYS_FSTAT] = ::sysFstat
        this[SYS_LSTAT] = ::sysLstat
        this[SYS_POLL] = ::sysPoll
        this[SYS_LSEEK] = ::sysLseek
        this[SYS_PIPE] = ::sysPipe2
        this[SYS_GETDENTS64] = ::sysGetdents64
        this[SYS_PIPE2] = ::sysPipe2
        this[SYS_DUP] = ::sysDup
        this[SYS_DUP2] = ::sysDup2
        this[SYS_MMAP] = ::sysMmap
        this[SYS_MPROTECT] = ::sysMprotect
        this[SYS_MUNMAP] = ::sysMunmap
        this[SYS_IOCTL] = ::sysIoctl
        this[SYS_READV] = ::sysReadv
        this[SYS_WRITEV] = ::sysWritev
        this[SYS_ACCESS] = ::sysAccess
        this[SYS_FACCESSAT] = ::sysFaccessat
        this[SYS_FACCESSAT2] = ::sysFaccessat2
        this[SYS_PSELECT6] = ::sysPselect6
        this[SYS_FCNTL] = ::sysFcntl
        this[SYS_RT_SIGACTION] = LinuxRuntimeSyscalls::rtSigaction
        this[SYS_RT_SIGPROCMASK] = LinuxRuntimeSyscalls::rtSigprocmask
        this[SYS_ARCH_PRCTL] = ::sysArchPrctl
        this[SYS_OPENAT] = ::sysOpenAt
        this[SYS_FCHOWNAT] = ::sysFchownat
        this[SYS_GETCWD] = ::sysGetCWD
        this[SYS_REBOOT] = ::sysReboot
        this[SYS_UNAME] = ::sysUname
        this[SYS_NANO_SLEEP] = LinuxRuntimeSyscalls::nanoSleep
        this[SYS_GETPID] = ::sysGetPID
        this[SYS_EXIT] = ::sysExit
        this[SYS_GETTIMEOFDAY] = LinuxRuntimeSyscalls::gettimeofday
        this[SYS_GETUID] = ::sysGetUID
        this[SYS_GETGID] = ::sysGetGID
        this[SYS_GETEUID] = ::sysGetEUID
        this[SYS_GETEGID] = ::sysGetEGID
        this[SYS_GETPPID] = ::sysGetPPID
        this[SYS_GETPGRP] = ::sysGetPGRP
        this[SYS_SETPGID] = ::sysSetPGID
        this[SYS_SETFSUID] = ::sysSetFSUID
        this[SYS_SETFSGID] = ::sysSetFSGID
        this[SYS_GETRESUID] = ::sysGetResUID
        this[SYS_GETRESGID] = ::sysGetResGID
        this[SYS_GETSID] = ::sysGetSID
        this[SYS_GETTID] = ::sysGetTID
        this[SYS_SET_TID_ADDRESS] = ::sysSetTidAddress
        this[SYS_EXIT_GROUP] = ::sysExitGroup
        this[SYS_CLOCK_GETTIME] = LinuxRuntimeSyscalls::clockGettime
        this[SYS_TIME] = LinuxRuntimeSyscalls::time
        this[SYS_NEWFSTATAT] = ::sysNewfstatat
        this[SYS_SET_ROBUST_LIST] = LinuxRuntimeSyscalls::setRobustList
        this[SYS_PRLIMIT64] = LinuxRuntimeSyscalls::prlimit64
        this[SYS_GETRANDOM] = LinuxRuntimeSyscalls::getrandom
        this[SYS_RSEQ] = LinuxRuntimeSyscalls::rseq
    }

    fun syscallHandle(regs: PtraceRegisters) {
        val number = regs[PtraceRegisters.IDX_RAX]
        val handler = if (number < handlers.size.toULong()) {
            handlers[number.toInt()]
        } else {
            null
        }
        val result = when {
            handler == null -> {
                errno(Errno.ENOSYS)
            }

            else -> ProcessManager.currentProcess()?.let { handler(regs, it) }
                ?: errno(Errno.ESRCH)
        }
        regs[PtraceRegisters.IDX_RAX] = result.toULong()
    }

    fun copyWordToUser(process: Process, address: ULong, value: ULong): Long {
        val bytes = ByteArray(ULong.SIZE_BYTES) { index ->
            (value shr (index * Byte.SIZE_BITS)).toByte()
        }
        return if (UserMemory(process.addressSpace, address).copyToUser(bytes)) {
            0L
        } else {
            errno(Errno.EFAULT)
        }
    }

    fun copyPath(process: Process, address: ULong): ByteArray? =
        UserMemory(process.addressSpace, address).copyCStringFromUser(PATH_MAX)

    fun userMemory(process: Process, base: ULong, offset: ULong): UserMemory? {
        if (offset > ULong.MAX_VALUE - base) {
            return null
        }
        return UserMemory(process.addressSpace, base + offset)
    }

    fun fileDescriptor(value: ULong): Int? =
        value.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()

    fun partialOrError(transferred: ULong, error: Int): Long =
        if (transferred == 0uL) errno(error) else transferred.toLong()

    fun errno(value: Int): Long = -value.toLong()

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
