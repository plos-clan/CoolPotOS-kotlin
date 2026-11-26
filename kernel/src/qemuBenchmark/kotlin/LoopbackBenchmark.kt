@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*

class LoopbackBenchmark(
    private val stream: Boolean,
    private val bytes: Int,
    private val members: Int = 1,
) : SyscallBenchmark(
    "loopback.${if (stream) "tcp" else "udp"}[bytes=$bytes]" +
        if (members == 1) "" else "[sockets=$members]",
) {
    override fun MemScope.operation(): () -> Int {
        val type = if (stream) SOCK_STREAM else SOCK_DGRAM
        val address = alloc<sockaddr_in>()
        address.sin_family = AF_INET.toUShort()
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
        address.sin_port = 0u
        val endpoint = address.ptr.reinterpret<sockaddr>()
        val size = sizeOf<sockaddr_in>().toUInt()
        val length = alloc<UIntVar>()
        length.value = size
        val sockets = allocArray<pollfd>(members)
        val enabled = alloc<IntVar>()
        enabled.value = 1
        val optionSize = sizeOf<IntVar>().toUInt()
        for (i in 0 until members) {
            val fd = socket(AF_INET, type, 0)
            check(fd >= 0)
            defer { close(fd) }
            if (members > 1) {
                val shared = setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, enabled.ptr, optionSize)
                check(shared == 0)
            }
            check(bind(fd, endpoint, size) == 0)
            if (i == 0) check(getsockname(fd, endpoint, length.ptr) == 0)
            sockets[i].fd = fd
            sockets[i].events = POLLIN.toShort()
        }
        val server = sockets[0].fd
        if (stream) check(listen(server, 1) == 0)
        val client = socket(AF_INET, type, 0)
        check(client >= 0)
        defer { close(client) }
        check(connect(client, endpoint, size) == 0)
        val receiver = when {
            stream -> {
                val fd = accept(server, null, null)
                check(fd >= 0)
                defer { close(fd) }
                check(setsockopt(client, IPPROTO_TCP, TCP_NODELAY, enabled.ptr, optionSize) == 0)
                fd
            }
            members == 1 -> server
            else -> {
                val probe = alloc<ByteVar>()
                probe.value = 42
                check(send(client, probe.ptr, 1u, 0) == 1L)
                check(poll(sockets, members.toULong(), 1000) == 1)
                val index = (0 until members).single { sockets[it].revents.toInt() and POLLIN != 0 }
                val fd = sockets[index].fd
                check(recv(fd, probe.ptr, 1u, 0) == 1L && probe.value == 42.toByte())
                fd
            }
        }
        val data = allocArray<ByteVar>(bytes)
        val received = allocArray<ByteVar>(bytes)
        val count = bytes.toULong()
        for (i in 0 until bytes) data[i] = i.toByte()
        return {
            check(send(client, data, count, 0) == bytes.toLong())
            var total = 0
            do {
                val remaining = (bytes - total).toULong()
                val n = recv(receiver, received + total, remaining, 0)
                check(n > 0)
                total += n.toInt()
            } while (stream && total < bytes)
            check(total == bytes && memcmp(data, received, count) == 0)
            total
        }
    }
}

class FileSendBenchmark(private val direct: Boolean, private val bytes: Int) :
    SyscallBenchmark("file.${if (direct) "sendfile" else "preadSend"}[bytes=$bytes]") {
    override fun MemScope.operation(): () -> Int {
        val file = open("/usr/bin/bash", O_RDONLY)
        check(file >= 0)
        defer { close(file) }
        val sockets = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0)
        val writer = sockets[0]
        val reader = sockets[1]
        defer { close(writer) }
        defer { close(reader) }
        val data = allocArray<ByteVar>(bytes)
        val expected = allocArray<ByteVar>(bytes)
        val count = bytes.toULong()
        val start = 7L
        val offset = alloc<LongVar>()
        check(pread(file, expected, count, start) == bytes.toLong())
        val sendfile = platform.linux.SYS_sendfile.toLong()
        return {
            if (direct) {
                offset.value = start
                val sent = syscall(sendfile, writer, file, offset.ptr, count)
                check(sent == bytes.toLong() && offset.value == start + bytes)
            } else {
                check(pread(file, data, count, start) == bytes.toLong())
                check(send(writer, data, count, 0) == bytes.toLong())
            }
            check(recv(reader, data, count, MSG_WAITALL) == bytes.toLong())
            check(memcmp(data, expected, count) == 0)
            bytes
        }
    }
}
