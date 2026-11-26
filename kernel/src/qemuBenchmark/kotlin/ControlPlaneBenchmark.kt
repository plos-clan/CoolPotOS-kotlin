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
