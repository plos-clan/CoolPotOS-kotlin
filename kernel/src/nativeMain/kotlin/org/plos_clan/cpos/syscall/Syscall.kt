@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.module.Vdso
import org.plos_clan.cpos.syscall.fs.EventFdSyscalls
import org.plos_clan.cpos.syscall.fs.EpollSyscalls
import org.plos_clan.cpos.syscall.fs.access
import org.plos_clan.cpos.syscall.fs.chdir
import org.plos_clan.cpos.syscall.fs.chmod
import org.plos_clan.cpos.syscall.fs.chown
import org.plos_clan.cpos.syscall.fs.chroot
import org.plos_clan.cpos.syscall.fs.close
import org.plos_clan.cpos.syscall.fs.closeRange
import org.plos_clan.cpos.syscall.fs.copyFileRange
import org.plos_clan.cpos.syscall.fs.dup
import org.plos_clan.cpos.syscall.fs.dup2
import org.plos_clan.cpos.syscall.fs.faccessAt
import org.plos_clan.cpos.syscall.fs.faccessAt2
import org.plos_clan.cpos.syscall.fs.fadvise64
import org.plos_clan.cpos.syscall.fs.fallocate
import org.plos_clan.cpos.syscall.fs.fchdir
import org.plos_clan.cpos.syscall.fs.fchmod
import org.plos_clan.cpos.syscall.fs.fchmodAt
import org.plos_clan.cpos.syscall.fs.fchmodAt2
import org.plos_clan.cpos.syscall.fs.fchown
import org.plos_clan.cpos.syscall.fs.fchownAt
import org.plos_clan.cpos.syscall.fs.fcntl
import org.plos_clan.cpos.syscall.fs.fdatasync
import org.plos_clan.cpos.syscall.fs.fgetxattr
import org.plos_clan.cpos.syscall.fs.flistxattr
import org.plos_clan.cpos.syscall.fs.fremovexattr
import org.plos_clan.cpos.syscall.fs.fsetxattr
import org.plos_clan.cpos.syscall.fs.fstat
import org.plos_clan.cpos.syscall.fs.fstatfs
import org.plos_clan.cpos.syscall.fs.fsync
import org.plos_clan.cpos.syscall.fs.ftruncate
import org.plos_clan.cpos.syscall.fs.getCwd
import org.plos_clan.cpos.syscall.fs.getdents64
import org.plos_clan.cpos.syscall.fs.getxattr
import org.plos_clan.cpos.syscall.fs.ioctl
import org.plos_clan.cpos.syscall.fs.lchown
import org.plos_clan.cpos.syscall.fs.lgetxattr
import org.plos_clan.cpos.syscall.fs.link
import org.plos_clan.cpos.syscall.fs.linkAt
import org.plos_clan.cpos.syscall.fs.listxattr
import org.plos_clan.cpos.syscall.fs.llistxattr
import org.plos_clan.cpos.syscall.fs.lremovexattr
import org.plos_clan.cpos.syscall.fs.lseek
import org.plos_clan.cpos.syscall.fs.lsetxattr
import org.plos_clan.cpos.syscall.fs.lstat
import org.plos_clan.cpos.syscall.fs.mkdir
import org.plos_clan.cpos.syscall.fs.mkdirAt
import org.plos_clan.cpos.syscall.fs.mknod
import org.plos_clan.cpos.syscall.fs.mknodAt
import org.plos_clan.cpos.syscall.fs.mount
import org.plos_clan.cpos.syscall.fs.nameToHandleAt
import org.plos_clan.cpos.syscall.fs.newFstatAt
import org.plos_clan.cpos.syscall.fs.open
import org.plos_clan.cpos.syscall.fs.openAt
import org.plos_clan.cpos.syscall.fs.pipe
import org.plos_clan.cpos.syscall.fs.pipe2
import org.plos_clan.cpos.syscall.fs.poll
import org.plos_clan.cpos.syscall.fs.ppoll
import org.plos_clan.cpos.syscall.fs.pread64
import org.plos_clan.cpos.syscall.fs.pselect6
import org.plos_clan.cpos.syscall.fs.pwrite64
import org.plos_clan.cpos.syscall.fs.read
import org.plos_clan.cpos.syscall.fs.readlink
import org.plos_clan.cpos.syscall.fs.readlinkAt
import org.plos_clan.cpos.syscall.fs.readv
import org.plos_clan.cpos.syscall.fs.removexattr
import org.plos_clan.cpos.syscall.fs.rename
import org.plos_clan.cpos.syscall.fs.renameAt
import org.plos_clan.cpos.syscall.fs.renameAt2
import org.plos_clan.cpos.syscall.fs.rmdir
import org.plos_clan.cpos.syscall.fs.setxattr
import org.plos_clan.cpos.syscall.fs.stat
import org.plos_clan.cpos.syscall.fs.statfs
import org.plos_clan.cpos.syscall.fs.statx
import org.plos_clan.cpos.syscall.fs.splice
import org.plos_clan.cpos.syscall.fs.symlink
import org.plos_clan.cpos.syscall.fs.symlinkAt
import org.plos_clan.cpos.syscall.fs.truncate
import org.plos_clan.cpos.syscall.fs.umask
import org.plos_clan.cpos.syscall.fs.umount2
import org.plos_clan.cpos.syscall.fs.unlink
import org.plos_clan.cpos.syscall.fs.unlinkAt
import org.plos_clan.cpos.syscall.fs.utimensAt
import org.plos_clan.cpos.syscall.fs.write
import org.plos_clan.cpos.syscall.fs.writev
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
    SOCKET(41, SocketSyscalls::socket),
    CONNECT(42, SocketSyscalls::connect, restartable = true),
    ACCEPT(43, SocketSyscalls::accept, restartable = true),
    SENDTO(44, SocketSyscalls::sendto, restartable = true),
    RECVFROM(45, SocketSyscalls::recvfrom, restartable = true),
    SENDMSG(46, SocketSyscalls::sendmsg, restartable = true),
    RECVMSG(47, SocketSyscalls::recvmsg, restartable = true),
    SHUTDOWN(48, SocketSyscalls::shutdown),
    BIND(49, SocketSyscalls::bind),
    LISTEN(50, SocketSyscalls::listen),
    GETSOCKNAME(51, SocketSyscalls::getsockname),
    GETPEERNAME(52, SocketSyscalls::getpeername),
    SOCKETPAIR(53, SocketSyscalls::socketpair),
    SETSOCKOPT(54, SocketSyscalls::setsockopt),
    GETSOCKOPT(55, SocketSyscalls::getsockopt),
    CLONE(56, ::clone),
    EXECVE(59, ::execve),
    EXIT(60, ::exit),
    WAIT4(61, ::wait4, restartable = true),
    KILL(62, SignalSyscalls::kill),
    UNAME(63, UtsSyscalls::uname),
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
    SETUID(105, ::setUid),
    SETGID(106, ::setGid),
    GETEUID(107, ::getEuid),
    GETEGID(108, ::getEgid),
    SETPGID(109, ::setPgid),
    GETPPID(110, ::getPpid),
    GETPGRP(111, ::getPgrp),
    SETSID(112, ::setSid),
    SETREUID(113, ::setReUid),
    SETREGID(114, ::setReGid),
    GETGROUPS(115, ::getGroups),
    SETGROUPS(116, ::setGroups),
    SETRESUID(117, ::setResUid),
    GETRESUID(118, ::getResUid),
    SETRESGID(119, ::setResGid),
    GETRESGID(120, ::getResGid),
    SETFSUID(122, ::setFsUid),
    SETFSGID(123, ::setFsGid),
    GETSID(124, ::getSid),
    CAPGET(125, ::capGet),
    CAPSET(126, ::capSet),
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
    CHROOT(161, ::chroot),
    MOUNT(165, ::mount),
    UMOUNT2(166, ::umount2),
    REBOOT(169, ::reboot),
    SET_HOSTNAME(170, UtsSyscalls::setHostname),
    SET_DOMAIN_NAME(171, UtsSyscalls::setDomainName),
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
    EPOLL_CREATE(213, EpollSyscalls::create),
    GETDENTS64(217, ::getdents64),
    SET_TID_ADDRESS(218, ::setTidAddress),
    FADVISE64(221, ::fadvise64),
    CLOCK_GETTIME(228, ::clockGetTime),
    CLOCK_GETRES(229, ::clockGetRes),
    EXIT_GROUP(231, ::exitGroup),
    EPOLL_WAIT(232, EpollSyscalls::wait),
    EPOLL_CTL(233, EpollSyscalls::control),
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
    SPLICE(275, ::splice, restartable = true),
    UTIMENSAT(280, ::utimensAt),
    EPOLL_PWAIT(281, EpollSyscalls::pwait),
    FALLOCATE(285, ::fallocate),
    ACCEPT4(288, SocketSyscalls::accept4, restartable = true),
    EVENTFD2(290, EventFdSyscalls::eventfd2),
    EPOLL_CREATE1(291, EpollSyscalls::create1),
    PIPE2(293, ::pipe2),
    RT_TGSIGQUEUEINFO(297, SignalSyscalls::rtTgsigqueueinfo),
    RECVMMSG(299, SocketSyscalls::recvmmsg, restartable = true),
    PRLIMIT64(302, ::prlimit64),
    NAME_TO_HANDLE_AT(303, ::nameToHandleAt),
    SENDMMSG(307, SocketSyscalls::sendmmsg, restartable = true),
    GETCPU(309, ::getCPU),
    RENAMEAT2(316, ::renameAt2),
    GETRANDOM(318, ::getRandom, restartable = true),
    COPY_FILE_RANGE(326, ::copyFileRange, restartable = true),
    STATX(332, ::statx),
    RSEQ(334, ::rseq),
    PIDFD_OPEN(434, PidFdSyscalls::open),
    CLONE3(435, ::clone3),
    CLOSE_RANGE(436, ::closeRange),
    FACCESSAT2(439, ::faccessAt2),
    EPOLL_PWAIT2(441, EpollSyscalls::pwait2),
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
            if(number != 12UL) println("SYSCALL: no implement $number")
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
