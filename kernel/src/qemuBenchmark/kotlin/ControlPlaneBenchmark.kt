@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.getpid
import platform.posix.getsid
import platform.posix.open
import platform.posix.pread
import platform.posix.syscall

object PidLookupBenchmark : SyscallBenchmark("process.getsid") {
    override fun MemScope.operation(): () -> Int {
        val pid = getpid()
        return { getsid(pid) }
    }
}

object TidLookupBenchmark : SyscallBenchmark("thread.getpriority") {
    override fun MemScope.operation(): () -> Int {
        val tid = syscall(SYS_gettid.toLong()).toUInt()
        return { getpriority(PRIO_PROCESS, tid) }
    }
}

object CachedReadBenchmark : SyscallBenchmark("pageCache.pread") {
    override fun MemScope.operation(): () -> Int {
        val file = open("/usr/bin/bash", O_RDONLY)
        check(file >= 0)
        defer { close(file) }
        val byte = alloc<ByteVar>().ptr
        return { pread(file, byte, 1uL, 0).toInt() }
    }
}

object PrivateFutexWakeBenchmark : SyscallBenchmark("futex.privateWake") {
    private const val WAKE_PRIVATE = 1 or 128

    override fun MemScope.operation(): () -> Int {
        val word = alloc<IntVar>()
        word.value = 0
        val address = word.ptr
        return { syscall(SYS_futex.toLong(), address, WAKE_PRIVATE, 1).toInt() }
    }
}

object EpollModifyBenchmark : SyscallBenchmark("epoll.modifyEventFd") {
    override fun MemScope.operation(): () -> Int {
        val epoll = epoll_create1(0)
        check(epoll >= 0)
        defer { close(epoll) }
        val target = eventfd(0, 0)
        check(target >= 0)
        defer { close(target) }
        val event = alloc<epoll_event>()
        event.events = EPOLLIN
        val interest = event.ptr
        check(epoll_ctl(epoll, EPOLL_CTL_ADD, target, interest) == 0)
        return { epoll_ctl(epoll, EPOLL_CTL_MOD, target, interest) }
    }
}

object IoctlNonblockBenchmark : SyscallBenchmark("ioctl.fionbio") {
    private const val FIONBIO = 0x5421

    override fun MemScope.operation(): () -> Int {
        val fd = eventfd(0, 0)
        check(fd >= 0)
        defer { close(fd) }
        val enabled = alloc<IntVar>()
        enabled.value = 1
        val argument = enabled.ptr
        return { syscall(SYS_ioctl.toLong(), fd, FIONBIO, argument).toInt() }
    }
}

class PollReadyBenchmark(private val count: Int) : SyscallBenchmark("poll.ready[fds=$count]") {
    override fun MemScope.operation(): () -> Int {
        val descriptors = allocArray<platform.posix.pollfd>(count)
        for (index in 0 until count) {
            val fd = eventfd(1, 0)
            check(fd >= 0)
            defer { close(fd) }
            descriptors[index].fd = fd
            descriptors[index].events = platform.posix.POLLIN.toShort()
        }
        val size = count.toULong()
        check(platform.posix.poll(descriptors, size, 0) == count)
        return { platform.posix.poll(descriptors, size, 0) }
    }
}

class SparseSelectBenchmark(private val descriptor: Int) :
    SyscallBenchmark("pselect6.sparse[fd=$descriptor]") {
    override fun MemScope.operation(): () -> Int {
        val source = eventfd(1, 0)
        check(source >= 0)
        defer { close(source) }
        val fd = platform.posix.fcntl(source, platform.posix.F_DUPFD, descriptor)
        check(fd == descriptor)
        defer { close(fd) }

        val words = fd / ULong.SIZE_BITS + 1
        val read = allocArray<ULongVar>(words)
        for (i in 0 until words) read[i] = 0uL
        read[words - 1] = 1uL shl (fd % ULong.SIZE_BITS)
        val timeout = alloc<platform.posix.timespec>()
        timeout.tv_sec = 0
        timeout.tv_nsec = 0
        val limit = timeout.ptr
        val number = SYS_pselect6.toLong()
        val poll = { syscall(number, fd + 1, read, null, null, limit, null).toInt() }
        check(poll() == 1)
        return poll
    }
}

object EpollReadyBenchmark : SyscallBenchmark("epoll.ready") {
    override fun MemScope.operation(): () -> Int {
        val epoll = epoll_create1(0)
        check(epoll >= 0)
        defer { close(epoll) }
        val fd = eventfd(1, 0)
        check(fd >= 0)
        defer { close(fd) }
        val event = alloc<epoll_event>()
        event.events = EPOLLIN
        event.data.u64 = fd.toULong()
        val entry = event.ptr
        check(epoll_ctl(epoll, EPOLL_CTL_ADD, fd, entry) == 0)
        val poll = { epoll_wait(epoll, entry, 1, 0) }
        check(poll() == 1 && event.events == EPOLLIN && event.data.u64 == fd.toULong())
        return poll
    }
}

object ClockSyscallBenchmark : SyscallBenchmark("clock.gettimeSyscall") {
    override fun MemScope.operation(): () -> Int {
        val result = alloc<platform.posix.timespec>().ptr
        return { syscall(SYS_clock_gettime.toLong(), platform.posix.CLOCK_MONOTONIC, result).toInt() }
    }
}
