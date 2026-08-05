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
private const val SYS_POLL = 7
private const val SYS_MMAP = 9
private const val SYS_MPROTECT = 10
private const val SYS_MUNMAP = 11
private const val SYS_IOCTL = 16
private const val SYS_READV = 19
private const val SYS_WRITEV = 20
private const val SYS_NANO_SLEEP = 35
private const val SYS_UNAME = 63
private const val SYS_GETCWD = 79
private const val SYS_ARCH_PRCTL = 158
private const val SYS_REBOOT = 169
private const val SYS_OPENAT = 257

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
        this[SYS_POLL] = ::sysPoll
        this[SYS_MMAP] = ::sysMmap
        this[SYS_MPROTECT] = ::sysMprotect
        this[SYS_MUNMAP] = ::sysMunmap
        this[SYS_IOCTL] = ::sysIoctl
        this[SYS_READV] = ::sysReadv
        this[SYS_WRITEV] = ::sysWritev
        this[SYS_ARCH_PRCTL] = ::sysArchPrctl
        this[SYS_OPENAT] = ::sysOpenAt
        this[SYS_GETCWD] = ::sysGetCWD
        this[SYS_REBOOT] = ::sysReboot
        this[SYS_UNAME] = ::sysUname
        this[SYS_NANO_SLEEP] = :: sysNanoSleep
    }

    fun syscallHandle(regs: PtraceRegisters) {
        val number = regs[PtraceRegisters.IDX_RAX]
        val handler = if (number < handlers.size.toULong()) {
            handlers[number.toInt()]
        } else {
            null
        }
        val result = when {
            handler == null -> errno(Errno.ENOSYS)
            else -> ProcessManager.currentProcess()?.let { handler(regs, it) }
                ?: errno(Errno.ESRCH)
        }
        regs[PtraceRegisters.IDX_RAX] = result.toULong()
    }

    fun copyWordToUser(process: Process, address: ULong, value: ULong): Long {
        val bytes = ByteArray(ULong.SIZE_BYTES) { index ->
            (value shr (index * Byte.SIZE_BITS)).toByte()
        }
        return if (UserMemory(process.vma, address).copyToUser(bytes)) {
            0L
        } else {
            errno(Errno.EFAULT)
        }
    }

    fun copyPath(process: Process, address: ULong): ByteArray? =
        UserMemory(process.vma, address).copyCStringFromUser(PATH_MAX)

    fun userMemory(process: Process, base: ULong, offset: ULong): UserMemory? {
        if (offset > ULong.MAX_VALUE - base) {
            return null
        }
        return UserMemory(process.vma, base + offset)
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
