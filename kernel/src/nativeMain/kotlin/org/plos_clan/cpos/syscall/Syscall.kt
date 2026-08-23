@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.module.Vdso
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.TaskState
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

private const val PATH_MAX = 4096

private typealias SyscallHandler = (PtraceRegisters, Process) -> Long
// The syscall epilogue selects IRET for VM and clears it before constructing the user frame.
private const val FORCE_IRET_FLAG = 0x0002_0000uL

private enum class LinuxSyscall(
    val number: Int,
    val handler: SyscallHandler,
    val restartable: Boolean = false,
) {
    READ(0, ::read, restartable = true),
    WRITE(1, ::write, restartable = true),
    OPEN(2, ::open, restartable = true),
    CLOSE(3, ::close),
    STAT(4, ::stat),
    FSTAT(5, ::fstat),
    LSTAT(6, ::lstat),
    POLL(7, ::poll),
    LSEEK(8, ::lseek),
    MMAP(9, ::mmap),
    MPROTECT(10, ::mprotect),
    MUNMAP(11, ::munmap),
    // 12 brk 系统调用不实现
    RT_SIGACTION(13, SignalSyscalls::rtSigaction),
    RT_SIGPROCMASK(14, SignalSyscalls::rtSigprocmask),
    RT_SIGRETURN(15, SignalSyscalls::rtSigreturn),
    IOCTL(16, ::ioctl),
    PREAD64(17, ::pread64, restartable = true),
    PWRITE64(18, ::pwrite64, restartable = true),
    READV(19, ::readv, restartable = true),
    WRITEV(20, ::writev, restartable = true),
    ACCESS(21, ::access),
    PIPE(22, ::pipe),
    DUP(32, ::dup),
    DUP2(33, ::dup2),
    PAUSE(34, SignalSyscalls::pause),
    NANO_SLEEP(35, ::nanoSleep),
    GETPID(39, ::getPid),
    CLONE(56, ::clone),
    EXECVE(59, ::execve),
    EXIT(60, ::exit),
    WAIT4(61, ::wait4, restartable = true),
    KILL(62, SignalSyscalls::kill),
    UNAME(63, ::uname),
    FCNTL(72, ::fcntl),
    FSYNC(74, ::fsync),
    FDATASYNC(75, ::fdatasync),
    TRUNCATE(76, ::truncate),
    FTRUNCATE(77, ::ftruncate),
    GETCWD(79, ::getCwd),
    CHDIR(80, ::chdir),
    FCHDIR(81, ::fchdir),
    RENAME(82, ::rename),
    MKDIR(83, ::mkdir),
    RMDIR(84, ::rmdir),
    LINK(86, ::link),
    UNLINK(87, ::unlink),
    SYMLINK(88, ::symlink),
    READLINK(89, ::readlink),
    CHMOD(90, ::chmod),
    FCHMOD(91, ::fchmod),
    CHOWN(92, ::chown),
    FCHOWN(93, ::fchown),
    LCHOWN(94, ::lchown),
    UMASK(95, ::umask),
    GETTIMEOFDAY(96, ::getTimeOfDay),
    SYSINFO(99, ::sysInfo),
    GETUID(102, ::getUid),
    GETGID(104, ::getGid),
    GETEUID(107, ::getEuid),
    GETEGID(108, ::getEgid),
    SETPGID(109, ::setPgid),
    GETPPID(110, ::getPpid),
    GETPGRP(111, ::getPgrp),
    SETSID(112, ::setSid),
    GETRESUID(118, ::getResUid),
    GETRESGID(120, ::getResGid),
    SETFSUID(122, ::setFsUid),
    SETFSGID(123, ::setFsGid),
    GETSID(124, ::getSid),
    RT_SIGPENDING(127, SignalSyscalls::rtSigpending),
    RT_SIGTIMEDWAIT(128, SignalSyscalls::rtSigtimedwait),
    RT_SIGQUEUEINFO(129, SignalSyscalls::rtSigqueueinfo),
    RT_SIGSUSPEND(130, SignalSyscalls::rtSigsuspend),
    SIGALTSTACK(131, SignalSyscalls::sigaltstack),
    MKNOD(133, ::mknod),
    STATFS(137, ::statfs),
    FSTATFS(138, ::fstatfs),
    PRCTL(157, ::prctl),
    ARCH_PRCTL(158, ::archPrctl),
    MOUNT(165, ::mount),
    UMOUNT2(166, ::umount2),
    REBOOT(169, ::reboot),
    GETTID(186, ::getTid),
    SETXATTR(188, ::setxattr),
    LSETXATTR(189, ::lsetxattr),
    FSETXATTR(190, ::fsetxattr),
    GETXATTR(191, ::getxattr),
    LGETXATTR(192, ::lgetxattr),
    FGETXATTR(193, ::fgetxattr),
    LISTXATTR(194, ::listxattr),
    LLISTXATTR(195, ::llistxattr),
    FLISTXATTR(196, ::flistxattr),
    REMOVEXATTR(197, ::removexattr),
    LREMOVEXATTR(198, ::lremovexattr),
    FREMOVEXATTR(199, ::fremovexattr),
    TKILL(200, SignalSyscalls::tkill),
    TIME(201, ::time),
    FUTEX(202, Futex::handle, restartable = true),
    G_AFFINITY(204, ::schedGetAffinity),
    GETDENTS64(217, ::getdents64),
    SET_TID_ADDRESS(218, ::setTidAddress),
    FADVISE64(221, ::fadvise64),
    CLOCK_GETTIME(228, ::clockGetTime),
    CLOCK_GETRES(229, ::clockGetRes),
    EXIT_GROUP(231, ::exitGroup),
    TGKILL(234, SignalSyscalls::tgkill),
    OPENAT(257, ::openAt, restartable = true),
    MKDIRAT(258, ::mkdirAt),
    MKNODAT(259, ::mknodAt),
    FCHOWNAT(260, ::fchownAt),
    NEWFSTATAT(262, ::newFstatAt),
    UNLINKAT(263, ::unlinkAt),
    RENAMEAT(264, ::renameAt),
    LINKAT(265, ::linkAt),
    SYMLINKAT(266, ::symlinkAt),
    READLINKAT(267, ::readlinkAt),
    FCHMODAT(268, ::fchmodAt),
    FACCESSAT(269, ::faccessAt),
    PSELECT6(270, ::pselect6),
    PPOLL(271, ::ppoll),
    SET_ROBUST_LIST(273, ::setRobustList),
    UTIMENSAT(280, ::utimensAt),
    FALLOCATE(285, ::fallocate),
    PIPE2(293, ::pipe2),
    RT_TGSIGQUEUEINFO(297, SignalSyscalls::rtTgsigqueueinfo),
    PRLIMIT64(302, ::prlimit64),
    GETCPU(309, ::getCPU),
    RENAMEAT2(316, ::renameAt2),
    GETRANDOM(318, ::getRandom, restartable = true),
    STATX(332, ::statx),
    RSEQ(334, ::rseq),
    CLONE3(435, ::clone3),
    FACCESSAT2(439, ::faccessAt2),
    FCHMODAT2(452, ::fchmodAt2),
}

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("syscall_handler")
fun syscallHandler(frame: COpaquePointer?) {
    Syscall.syscallHandle(PtraceRegisters(requireNotNull(frame).reinterpret()))
}

object Syscall {
    private val definitions = arrayOfNulls<LinuxSyscall>(
        LinuxSyscall.entries.maxOf(LinuxSyscall::number) + 1,
    ).apply {
        LinuxSyscall.entries.forEach { syscall ->
            check(this[syscall.number] == null) { "duplicate syscall ${syscall.number}" }
            this[syscall.number] = syscall
        }
    }

    fun syscallHandle(regs: PtraceRegisters) {
        val number = regs[PtraceRegisters.IDX_RAX]
        val thread = ProcessManager.currentThread()
        if (number == Vdso.SIGNAL_GATEWAY_SYSCALL) {
            if (thread != null && SignalDelivery.deliverGateway(regs, thread)) return
            regs[PtraceRegisters.IDX_RAX] = errno(Errno.ENOSYS).toULong()
            return
        }
        val definition = if (number < definitions.size.toULong()) {
            definitions[number.toInt()]
        } else {
            null
        }
        val result = if (definition == null) {
            println("SYSCALL: no implement $number")
            errno(Errno.ENOSYS)
        } else {
            val process = thread?.process
            if (process == null) errno(Errno.ESRCH) else definition.handler(regs, process)
        }
        val frameInstalled = regs.signalFrameInstalled
        if (!frameInstalled) regs[PtraceRegisters.IDX_RAX] = result.toULong()
        if (thread != null && thread.state != TaskState.ZOMBIE &&
            ProcessManager.currentThread() === thread &&
            !frameInstalled
        ) {
            SignalDelivery.deliverPending(regs, thread)
        }
        if (regs[PtraceRegisters.IDX_FUNC] == PtraceRegisters.SIGNAL_RETURN) {
            regs[PtraceRegisters.IDX_RFLAGS] =
                regs[PtraceRegisters.IDX_RFLAGS] or FORCE_IRET_FLAG
        }
    }

    internal fun isRestartable(number: ULong): Boolean =
        number < definitions.size.toULong() && definitions[number.toInt()]?.restartable == true

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
