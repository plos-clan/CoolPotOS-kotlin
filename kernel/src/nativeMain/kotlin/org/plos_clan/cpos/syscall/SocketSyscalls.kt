@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.sock.AbstractSocket
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDeadline
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.sock.SocketLinger
import org.plos_clan.cpos.fs.sock.SocketReceiveRequest
import org.plos_clan.cpos.fs.sock.SocketSendRequest
import org.plos_clan.cpos.fs.sock.SocketShutdownMode
import org.plos_clan.cpos.fs.sock.SocketType
import org.plos_clan.cpos.fs.sock.UnspecifiedSocketAddress
import org.plos_clan.cpos.fs.sock.UnixAncillaryData
import org.plos_clan.cpos.fs.sock.UnixCredentials
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.network.IcmpProtocol
import org.plos_clan.cpos.network.IpProtocol
import org.plos_clan.cpos.network.NetlinkProtocolKind
import org.plos_clan.cpos.network.NetlinkProtocols
import org.plos_clan.cpos.network.SocketAddressAbi
import org.plos_clan.cpos.network.SocketAddressOutput
import org.plos_clan.cpos.network.SocketConstants
import org.plos_clan.cpos.network.TcpProtocol
import org.plos_clan.cpos.network.UdpProtocol
import org.plos_clan.cpos.network.SocketConstants.MSG_CMSG_CLOEXEC
import org.plos_clan.cpos.network.SocketConstants.MSG_CTRUNC
import org.plos_clan.cpos.network.SocketConstants.MSG_DONTWAIT
import org.plos_clan.cpos.network.SocketConstants.MSG_EOR
import org.plos_clan.cpos.network.SocketConstants.MSG_MORE
import org.plos_clan.cpos.network.SocketConstants.MSG_NOSIGNAL
import org.plos_clan.cpos.network.SocketConstants.MSG_PEEK
import org.plos_clan.cpos.network.SocketConstants.MSG_TRUNC
import org.plos_clan.cpos.network.SocketConstants.MSG_WAITALL
import org.plos_clan.cpos.network.SocketConstants.RECEIVE_FLAGS
import org.plos_clan.cpos.network.SocketConstants.SEND_FLAGS
import org.plos_clan.cpos.network.SocketConstants.SOCK_CLOEXEC
import org.plos_clan.cpos.network.SocketConstants.SOCK_NONBLOCK
import org.plos_clan.cpos.network.SocketConstants.SOCK_SUPPORTED_FLAGS
import org.plos_clan.cpos.network.SocketConstants.SOCK_TYPE_MASK
import org.plos_clan.cpos.network.SocketConstants.SOL_SOCKET
import org.plos_clan.cpos.network.SocketControlMessages
import org.plos_clan.cpos.network.UserMessageHeader
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.CapManager
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal object SocketSyscalls {
    private data class CreationOptions(
        val domain: SocketDomain,
        val type: SocketType,
        val protocol: Int,
        val nonBlocking: Boolean,
        val descriptorFlags: ULong,
    )

    private enum class SetSocketOption(val abiValue: Int, val size: Int) {
        SEND_BUFFER(SocketConstants.SO_SNDBUF, Int.SIZE_BYTES),
        RECEIVE_BUFFER(SocketConstants.SO_RCVBUF, Int.SIZE_BYTES),
        PASS_CREDENTIALS(SocketConstants.SO_PASSCRED, Int.SIZE_BYTES),
        RECEIVE_LOW_WATERMARK(SocketConstants.SO_RCVLOWAT, Int.SIZE_BYTES),
        RECEIVE_TIMEOUT(SocketConstants.SO_RCVTIMEO, Long.SIZE_BYTES * 2),
        SEND_TIMEOUT(SocketConstants.SO_SNDTIMEO, Long.SIZE_BYTES * 2),
        REUSE_ADDRESS(SocketConstants.SO_REUSEADDR, Int.SIZE_BYTES),
        BROADCAST(SocketConstants.SO_BROADCAST, Int.SIZE_BYTES),
        KEEP_ALIVE(SocketConstants.SO_KEEPALIVE, Int.SIZE_BYTES),
        LINGER(SocketConstants.SO_LINGER, Int.SIZE_BYTES * 2),
        ;

        companion object {
            fun fromAbi(value: Int): SetSocketOption? = entries.firstOrNull {
                it.abiValue == value
            }
        }
    }

    fun socket(regs: PtraceRegisters, process: Process): Long {
        val options = when (val result = creationOptions(
            regs[PtraceRegisters.IDX_RDI],
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val context = process.context ?: return errno(Errno.ENOENT)
        val caller = process.vfsOperationContext
        val fileResult = when (options.domain) {
            SocketDomain.UNIX -> FileSystemManager.vfs.createUnixSocket(
                caller,
                context,
                options.type,
                options.nonBlocking,
                credentials(process),
            )
            SocketDomain.IPV4 -> {
                val socket = when (options.protocol) {
                    IpProtocol.ICMP.number.toInt() -> {
                        val thread = ProcessManager.currentThread()
                        if (process.credentials.userIds.effective != 0 &&
                            (thread == null || !CapManager.hasAllCapability(
                                thread,
                                CapEnum.NET_RAW,
                            ))
                        ) return errno(Errno.EPERM)
                        IcmpProtocol.createSocket()
                    }
                    IpProtocol.TCP.number.toInt() -> TcpProtocol.createSocket()
                    IpProtocol.UDP.number.toInt() -> UdpProtocol.createSocket()
                    else -> error("validated IPv4 protocol ${options.protocol}")
                }
                FileSystemManager.vfs.openSocket(caller, context, socket, options.nonBlocking)
            }
            SocketDomain.NETLINK -> {
                val socket = NetlinkProtocols.createSocket(options.protocol, options.type)
                    ?: return errno(Errno.EPROTONOSUPPORT)
                FileSystemManager.vfs.openSocket(caller, context, socket, options.nonBlocking)
            }
        }
        val file = when (fileResult) {
            is VfsResult.Ok -> fileResult.value
            is VfsResult.Err -> return errno(fileResult.error.errno)
        }
        val fd = process.fdTable.install(file, options.descriptorFlags)
        if (fd == null) {
            file.release()
            return errno(Errno.EMFILE)
        }
        return fd.toLong()
    }

    fun socketpair(regs: PtraceRegisters, process: Process): Long {
        val options = when (val result = creationOptions(
            regs[PtraceRegisters.IDX_RDI],
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        if (options.domain != SocketDomain.UNIX) return errno(Errno.EAFNOSUPPORT)
        val context = process.context ?: return errno(Errno.ENOENT)
        val caller = process.vfsOperationContext
        val pair = when (val result = FileSystemManager.vfs.createUnixSocketPair(
            caller,
            context,
            options.type,
            credentials(process),
            options.nonBlocking,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val files = listOf(pair.first, pair.second)
        val descriptors = process.fdTable.installAll(files, options.descriptorFlags)
        if (descriptors == null) {
            files.forEach(OpenFileDescription::release)
            return errno(Errno.EMFILE)
        }
        val output = ByteArray(Int.SIZE_BYTES * 2).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, descriptors[0].toUInt())
                writeU32(Int.SIZE_BYTES, descriptors[1].toUInt())
            }
        }
        if (!UserMemory(
                process.addressSpace,
                regs[PtraceRegisters.IDX_R10],
            ).copyToUser(output)
        ) {
            descriptors.forEach { descriptor ->
                process.fdTable.close(caller, descriptor)
            }
            return errno(Errno.EFAULT)
        }
        return 0L
    }

    fun bind(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val address = when (val decoded = SocketAddressAbi.read(
            process,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
        )) {
            is VfsResult.Ok -> decoded.value
            is VfsResult.Err -> return@withSocket errno(decoded.error.errno)
        }
        if (address.domain != socket.domain) return@withSocket errno(Errno.EAFNOSUPPORT)
        when (val result = socket.bindSocket(process, address)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun connect(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val decoded = SocketAddressAbi.read(
            process,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
            allowUnspec = true,
        )
        if (decoded is VfsResult.Err) return@withSocket errno(decoded.error.errno)
        val decodedAddress = (decoded as VfsResult.Ok).value
        val address = if (decodedAddress == UnspecifiedSocketAddress) null else decodedAddress
        if (address != null && address.domain != socket.domain) {
            return@withSocket errno(Errno.EAFNOSUPPORT)
        }
        val nonBlocking = file.getStatusFlags() and OpenFlags.O_NONBLOCK != 0
        when (val result = socket.connectSocket(process, address, nonBlocking)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun listen(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val backlog = regs[PtraceRegisters.IDX_RSI].toInt()
        when (val result = socket.listenSocket(process, backlog)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun accept(regs: PtraceRegisters, process: Process): Long =
        accept(process, regs, flagsValue = 0uL)

    fun accept4(regs: PtraceRegisters, process: Process): Long =
        accept(process, regs, regs[PtraceRegisters.IDX_R10])

    fun getsockname(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val output = when (val result = SocketAddressOutput.prepare(
            process,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
            optional = false,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return@withSocket errno(result.error.errno)
        }
        if (output.write(socket.localAddress())) 0L else errno(Errno.EFAULT)
    }

    fun getpeername(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val output = when (val result = SocketAddressOutput.prepare(
            process,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
            optional = false,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return@withSocket errno(result.error.errno)
        }
        val address = when (val result = socket.peerAddress()) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return@withSocket errno(result.error.errno)
        }
        if (output.write(address)) 0L else errno(Errno.EFAULT)
    }

    fun shutdown(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val mode = when (regs[PtraceRegisters.IDX_RSI]) {
            0uL -> SocketShutdownMode.READ
            1uL -> SocketShutdownMode.WRITE
            2uL -> SocketShutdownMode.BOTH
            else -> return@withSocket errno(Errno.EINVAL)
        }
        when (val result = socket.shutdownSocket(mode)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun sendto(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        send(
            process,
            file,
            socket,
            regs[PtraceRegisters.IDX_RSI],
            regs[PtraceRegisters.IDX_RDX],
            regs[PtraceRegisters.IDX_R10],
            regs[PtraceRegisters.IDX_R8],
            regs[PtraceRegisters.IDX_R9],
            UnixAncillaryData(),
        )
    }

    fun recvfrom(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val flags = receiveFlags(regs[PtraceRegisters.IDX_R10])
            ?: return@withSocket errno(Errno.EINVAL)
        val count = minOf(regs[PtraceRegisters.IDX_RDX], FsConstants.MAX_RW_COUNT).toInt()
        val destination = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
            .prepareWrite(0, count) ?: return@withSocket errno(Errno.EFAULT)
        val output = when (val result = SocketAddressOutput.prepare(
            process,
            regs[PtraceRegisters.IDX_R8],
            regs[PtraceRegisters.IDX_R9],
            optional = true,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return@withSocket errno(result.error.errno)
        }
        val received = socket.receiveSocket(
            SocketReceiveRequest(
                destination,
                0,
                count,
                nonBlocking = isNonBlocking(file, flags),
                peek = flags and MSG_PEEK != 0,
                waitAll = flags and MSG_WAITALL != 0,
                returnFullLength = flags and MSG_TRUNC != 0,
            ),
        )
        val result = when (received) {
            is VfsResult.Ok -> received.value
            is VfsResult.Err -> return@withSocket errno(received.error.errno)
        }
        result.ancillary?.release()
        if (!output.write(result.source)) return@withSocket errno(Errno.EFAULT)
        result.bytes.toLong()
    }

    fun sendmsg(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val flags = sendFlags(regs[PtraceRegisters.IDX_RDX])
            ?: return@withSocket errno(Errno.EINVAL)
        sendMessage(
            process,
            file,
            socket,
            regs[PtraceRegisters.IDX_RSI],
            flags,
        )
    }

    fun recvmsg(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val flags = receiveFlags(regs[PtraceRegisters.IDX_RDX])
            ?: return@withSocket errno(Errno.EINVAL)
        receiveMessage(
            process,
            file,
            socket,
            regs[PtraceRegisters.IDX_RSI],
            flags,
        )
    }

    fun sendmmsg(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val countValue = regs[PtraceRegisters.IDX_RDX]
        if (countValue > FsConstants.MAX_IO_VECTORS.toULong()) {
            return@withSocket errno(Errno.EINVAL)
        }
        val flags = sendFlags(regs[PtraceRegisters.IDX_R10])
            ?: return@withSocket errno(Errno.EINVAL)
        val base = regs[PtraceRegisters.IDX_RSI]
        var sent = 0
        while (sent < countValue.toInt()) {
            val header = multiMessageAddress(base, sent) ?: return@withSocket if (sent == 0) {
                errno(Errno.EFAULT)
            } else {
                sent.toLong()
            }
            val lengthMemory = UserMemory(process.addressSpace, header +
                SocketConstants.MESSAGE_HEADER_SIZE.toULong())
            if (!lengthMemory.isWritable(UInt.SIZE_BYTES)) {
                return@withSocket if (sent == 0) errno(Errno.EFAULT) else sent.toLong()
            }
            val result = sendMessage(process, file, socket, header, flags)
            if (result < 0) return@withSocket if (sent == 0) result else sent.toLong()
            val length = ByteArray(UInt.SIZE_BYTES)
            LittleEndianBuffer(length).writeU32(0, result.toUInt())
            if (!lengthMemory.copyToUser(length)) {
                return@withSocket if (sent == 0) errno(Errno.EFAULT) else sent.toLong()
            }
            sent++
        }
        sent.toLong()
    }

    fun recvmmsg(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { file, socket ->
        val countValue = regs[PtraceRegisters.IDX_RDX]
        if (countValue > FsConstants.MAX_IO_VECTORS.toULong()) {
            return@withSocket errno(Errno.EINVAL)
        }
        val requestedFlags = receiveFlags(regs[PtraceRegisters.IDX_R10])
            ?: return@withSocket errno(Errno.EINVAL)
        val timeout = when (val result = ReceiveTimeout.read(
            process,
            regs[PtraceRegisters.IDX_R8],
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return@withSocket errno(result.error.errno)
        }
        val base = regs[PtraceRegisters.IDX_RSI]
        var received = 0
        var flags = requestedFlags
        while (received < countValue.toInt()) {
            val header = multiMessageAddress(base, received)
                ?: return@withSocket finishReceiveTimeout(timeout, received, Errno.EFAULT)
            val lengthMemory = UserMemory(process.addressSpace, header +
                SocketConstants.MESSAGE_HEADER_SIZE.toULong())
            if (!lengthMemory.isWritable(UInt.SIZE_BYTES)) {
                return@withSocket finishReceiveTimeout(timeout, received, Errno.EFAULT)
            }
            val result = receiveMessage(process, file, socket, header, flags, timeout?.deadline)
            if (result < 0) {
                val error = (-result).toInt()
                val timedOut = error == Errno.EAGAIN && !isNonBlocking(file, flags) &&
                    timeout?.expired() == true
                return@withSocket finishReceiveTimeout(
                    timeout,
                    received,
                    error.takeUnless { timedOut },
                )
            }
            val length = ByteArray(UInt.SIZE_BYTES)
            LittleEndianBuffer(length).writeU32(0, result.toUInt())
            if (!lengthMemory.copyToUser(length)) {
                return@withSocket finishReceiveTimeout(timeout, received, Errno.EFAULT)
            }
            received++
            if (requestedFlags and SocketConstants.MSG_WAITFORONE != 0) {
                flags = flags or MSG_DONTWAIT
            }
        }
        finishReceiveTimeout(timeout, received, null)
    }

    fun setsockopt(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val level = regs[PtraceRegisters.IDX_RSI]
        val option = regs[PtraceRegisters.IDX_RDX]
        val length = regs[PtraceRegisters.IDX_R8]
        if (level > Int.MAX_VALUE.toULong() || option > Int.MAX_VALUE.toULong() ||
            length > MAX_SOCKET_OPTION_SIZE.toULong()
        ) {
            return@withSocket errno(Errno.EINVAL)
        }
        if (level.toInt() != SOL_SOCKET) {
            val bytes = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_R10])
                .copyFromUser(length.toInt()) ?: return@withSocket errno(Errno.EFAULT)
            return@withSocket when (val result = socket.setProtocolOption(
                process,
                level.toInt(),
                option.toInt(),
                bytes,
            )) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
        val selected = SetSocketOption.fromAbi(option.toInt())
            ?: return@withSocket errno(Errno.ENOPROTOOPT)
        if (length < selected.size.toULong()) return@withSocket errno(Errno.EINVAL)
        val bytes = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_R10])
            .copyFromUser(selected.size) ?: return@withSocket errno(Errno.EFAULT)
        val value = LittleEndianBuffer(bytes).readU32(0).toInt()
        val result = when (selected) {
            SetSocketOption.SEND_BUFFER -> if (value < 0) {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                socket.setSendBufferSize(value)
            }
            SetSocketOption.RECEIVE_BUFFER -> if (value < 0) {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                socket.setReceiveBufferSize(value)
            }
            SetSocketOption.PASS_CREDENTIALS -> socket.setPassCredentials(value != 0)
            SetSocketOption.RECEIVE_LOW_WATERMARK -> if (value <= 0) {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                socket.setReceiveLowWatermark(value)
            }
            SetSocketOption.RECEIVE_TIMEOUT -> when (val timeout = socketTimeout(bytes)) {
                is VfsResult.Ok -> socket.setReceiveTimeout(timeout.value)
                is VfsResult.Err -> timeout
            }
            SetSocketOption.SEND_TIMEOUT -> when (val timeout = socketTimeout(bytes)) {
                is VfsResult.Ok -> socket.setSendTimeout(timeout.value)
                is VfsResult.Err -> timeout
            }
            SetSocketOption.REUSE_ADDRESS -> socket.setReuseAddress(value != 0)
            SetSocketOption.BROADCAST -> socket.setBroadcast(value != 0)
            SetSocketOption.KEEP_ALIVE -> socket.setKeepAlive(value != 0)
            SetSocketOption.LINGER -> socket.setLinger(
                SocketLinger(
                    value != 0,
                    LittleEndianBuffer(bytes).readU32(Int.SIZE_BYTES).toInt(),
                ),
            )
        }
        when (result) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    }

    fun getsockopt(regs: PtraceRegisters, process: Process): Long = withSocket(
        process,
        regs[PtraceRegisters.IDX_RDI],
    ) { _, socket ->
        val level = regs[PtraceRegisters.IDX_RSI]
        val option = regs[PtraceRegisters.IDX_RDX]
        if (level > Int.MAX_VALUE.toULong() || option > Int.MAX_VALUE.toULong()) {
            return@withSocket errno(Errno.EINVAL)
        }
        val value = if (level.toInt() != SOL_SOCKET) {
            when (val result = socket.getProtocolOption(level.toInt(), option.toInt())) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@withSocket errno(result.error.errno)
            }
        } else {
            val options = socket.socketOptions()
            when (option.toInt()) {
                SocketConstants.SO_TYPE -> intOption(socket.socketType.abiValue)
                SocketConstants.SO_ERROR -> intOption(socket.takeError()?.errno ?: 0)
                SocketConstants.SO_SNDBUF -> intOption(options.sendBufferSize)
                SocketConstants.SO_RCVBUF -> intOption(options.receiveBufferSize)
                SocketConstants.SO_PASSCRED -> intOption(if (options.passCredentials) 1 else 0)
                SocketConstants.SO_RCVLOWAT -> intOption(options.receiveLowWatermark)
                SocketConstants.SO_SNDLOWAT -> intOption(1)
                SocketConstants.SO_RCVTIMEO -> timevalOption(options.receiveTimeoutNanos)
                SocketConstants.SO_SNDTIMEO -> timevalOption(options.sendTimeoutNanos)
                SocketConstants.SO_ACCEPTCONN -> intOption(if (socket.isListening()) 1 else 0)
                SocketConstants.SO_PROTOCOL -> intOption(socket.protocol)
                SocketConstants.SO_DOMAIN -> intOption(socket.domain.abiValue)
                SocketConstants.SO_REUSEADDR -> intOption(if (options.reuseAddress) 1 else 0)
                SocketConstants.SO_BROADCAST -> intOption(if (options.broadcast) 1 else 0)
                SocketConstants.SO_KEEPALIVE -> intOption(if (options.keepAlive) 1 else 0)
                SocketConstants.SO_LINGER -> lingerOption(options.linger)
                SocketConstants.SO_PEERCRED -> when (val result = socket.peerCredentials()) {
                    is VfsResult.Ok -> credentialOption(result.value)
                    is VfsResult.Err -> return@withSocket errno(result.error.errno)
                }
                else -> return@withSocket errno(Errno.ENOPROTOOPT)
            }
        }
        copySocketOption(
            process,
            regs[PtraceRegisters.IDX_R10],
            regs[PtraceRegisters.IDX_R8],
            value,
        )
    }

    private fun accept(process: Process, regs: PtraceRegisters, flagsValue: ULong): Long =
        withSocket(process, regs[PtraceRegisters.IDX_RDI]) { file, socket ->
            if (flagsValue > Int.MAX_VALUE.toULong()) return@withSocket errno(Errno.EINVAL)
            val flags = flagsValue.toInt()
            if (flags and (SOCK_NONBLOCK or SOCK_CLOEXEC).inv() != 0) {
                return@withSocket errno(Errno.EINVAL)
            }
            val output = when (val result = SocketAddressOutput.prepare(
                process,
                regs[PtraceRegisters.IDX_RSI],
                regs[PtraceRegisters.IDX_RDX],
                optional = true,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@withSocket errno(result.error.errno)
            }
            val operationNonBlocking = flags and SOCK_NONBLOCK != 0 ||
                file.getStatusFlags() and OpenFlags.O_NONBLOCK != 0
            val accepted = when (val result = socket.acceptSocket(process, operationNonBlocking)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@withSocket errno(result.error.errno)
            }
            val acceptedSocket = accepted.socket
            val context = process.context
            if (context == null) {
                acceptedSocket.release()
                return@withSocket errno(Errno.ENOENT)
            }
            val acceptedFile = when (val result = FileSystemManager.vfs.openSocket(
                process.vfsOperationContext,
                context,
                acceptedSocket,
                nonBlocking = flags and SOCK_NONBLOCK != 0,
            )) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> {
                    acceptedSocket.release()
                    return@withSocket errno(result.error.errno)
                }
            }
            val descriptorFlags = if (flags and SOCK_CLOEXEC != 0) {
                FileDescriptorFlags.FD_CLOEXEC
            } else {
                0uL
            }
            val descriptor = process.fdTable.install(acceptedFile, descriptorFlags)
            if (descriptor == null) {
                acceptedFile.release()
                return@withSocket errno(Errno.EMFILE)
            }
            if (!output.write(accepted.peerAddress)) {
                process.fdTable.close(process.vfsOperationContext, descriptor)
                return@withSocket errno(Errno.EFAULT)
            }
            descriptor.toLong()
        }

    private fun sendMessage(
        process: Process,
        file: OpenFileDescription,
        socket: AbstractSocket,
        headerAddress: ULong,
        flags: Int,
    ): Long {
        val header = when (val result = UserMessageHeader.read(process, headerAddress, writable = false)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val vector = header.vector(process) ?: return errno(Errno.EFAULT)
        val source = vector.prepareRead(0, vector.size) ?: return errno(Errno.EFAULT)
        val ancillary = when (val result = SocketControlMessages.read(
            process,
            header.controlAddress,
            header.controlLength,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        return sendPrepared(
            process,
            file,
            socket,
            source,
            vector.size,
            flags,
            header.nameAddress,
            if (header.nameAddress == 0uL) 0uL else header.nameLength.toULong(),
            ancillary,
        )
    }

    private fun receiveMessage(
        process: Process,
        file: OpenFileDescription,
        socket: AbstractSocket,
        headerAddress: ULong,
        flags: Int,
        deadline: SocketDeadline? = null,
    ): Long {
        val header = when (val result = UserMessageHeader.read(process, headerAddress, writable = true)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val vector = header.vector(process) ?: return errno(Errno.EFAULT)
        val destination = vector.prepareWrite(0, vector.size) ?: return errno(Errno.EFAULT)
        val addressOutput = when (val result = SocketAddressOutput.prepareMessage(
            process,
            header.nameAddress,
            header.nameLength,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        if (header.controlLength != 0 && header.controlAddress == 0uL) {
            return errno(Errno.EFAULT)
        }
        val passCredentials = socket.socketOptions().passCredentials
        val received = socket.receiveSocket(
            SocketReceiveRequest(
                destination,
                0,
                vector.size,
                nonBlocking = isNonBlocking(file, flags),
                peek = flags and MSG_PEEK != 0,
                waitAll = flags and MSG_WAITALL != 0,
                returnFullLength = flags and MSG_TRUNC != 0,
                deadline = deadline,
            ),
        )
        val result = when (received) {
            is VfsResult.Ok -> received.value
            is VfsResult.Err -> return errno(received.error.errno)
        }
        val nameLength = when (val name = addressOutput.writeMessage(result.source)) {
            is VfsResult.Ok -> name.value
            is VfsResult.Err -> {
                result.ancillary?.release()
                return errno(name.error.errno)
            }
        }
        val control = when (val written = SocketControlMessages.write(
            process,
            header.controlAddress,
            header.controlLength,
            result,
            passCredentials,
            flags and MSG_CMSG_CLOEXEC != 0,
        )) {
            is VfsResult.Ok -> written.value
            is VfsResult.Err -> return errno(written.error.errno)
        }
        var outputFlags = 0
        if (result.truncated) outputFlags = outputFlags or MSG_TRUNC
        if (result.endOfRecord) outputFlags = outputFlags or MSG_EOR
        if (control.truncated) outputFlags = outputFlags or MSG_CTRUNC
        if (!header.writeResult(nameLength, control.length, outputFlags)) {
            control.installedDescriptors.forEach { descriptor ->
                process.fdTable.close(process.vfsOperationContext, descriptor)
            }
            return errno(Errno.EFAULT)
        }
        return result.bytes.toLong()
    }

    private fun send(
        process: Process,
        file: OpenFileDescription,
        socket: AbstractSocket,
        bufferAddress: ULong,
        countValue: ULong,
        flagsValue: ULong,
        addressPointer: ULong,
        addressLength: ULong,
        ancillary: UnixAncillaryData,
    ): Long {
        val flags = sendFlags(flagsValue)
        if (flags == null) {
            ancillary.release()
            return errno(Errno.EINVAL)
        }
        val count = minOf(countValue, FsConstants.MAX_RW_COUNT).toInt()
        val source = UserMemory(process.addressSpace, bufferAddress).prepareRead(0, count)
        if (source == null) {
            ancillary.release()
            return errno(Errno.EFAULT)
        }
        return sendPrepared(
            process,
            file,
            socket,
            source,
            count,
            flags,
            addressPointer,
            addressLength,
            ancillary,
        )
    }

    private fun sendPrepared(
        process: Process,
        file: OpenFileDescription,
        socket: AbstractSocket,
        source: PreparedBufferSource,
        count: Int,
        flags: Int,
        addressPointer: ULong,
        addressLength: ULong,
        ancillary: UnixAncillaryData,
    ): Long {
        if (socket.domain != SocketDomain.UNIX && !ancillary.isEmpty) {
            ancillary.release()
            return errno(Errno.EINVAL)
        }
        val target = when (val result = messageAddress(
            process,
            socket,
            addressPointer,
            addressLength,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                ancillary.release()
                return errno(result.error.errno)
            }
        }
        return socket.sendSocket(
            SocketSendRequest(
                process,
                source,
                0,
                count,
                ancillary,
                target,
                isNonBlocking(file, flags),
                flags and MSG_NOSIGNAL != 0,
                flags and MSG_MORE != 0,
            ),
        ).raw
    }

    private fun messageAddress(
        process: Process,
        socket: AbstractSocket,
        addressPointer: ULong,
        addressLength: ULong,
    ): VfsResult<SocketAddress?> {
        if (addressPointer == 0uL) {
            return if (addressLength == 0uL) VfsResult.Ok(null)
            else VfsResult.Err(VfsError.FAULT)
        }
        val address = when (val result = SocketAddressAbi.read(
            process,
            addressPointer,
            addressLength,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return if (address.domain == socket.domain) VfsResult.Ok(address)
        else VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
    }

    private fun multiMessageAddress(base: ULong, index: Int): ULong? {
        val offset = index.toULong() * SocketConstants.MULTI_MESSAGE_HEADER_SIZE.toULong()
        return if (offset <= ULong.MAX_VALUE - base) base + offset else null
    }

    private fun finishReceiveTimeout(
        timeout: ReceiveTimeout?,
        count: Int,
        error: Int?,
    ): Long {
        if (timeout?.writeRemaining() == false && count == 0) return errno(Errno.EFAULT)
        return if (count != 0) count.toLong() else error?.let(::errno) ?: 0L
    }

    private inline fun withSocket(
        process: Process,
        descriptorValue: ULong,
        operation: (OpenFileDescription, AbstractSocket) -> Long,
    ): Long {
        val descriptor = fileDescriptor(descriptorValue) ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        val socket = file.backend as? AbstractSocket
        if (socket == null) {
            file.release()
            return errno(Errno.ENOTSOCK)
        }
        return try {
            operation(file, socket)
        } finally {
            file.release()
        }
    }

    private fun creationOptions(
        domainValue: ULong,
        typeValue: ULong,
        protocolValue: ULong,
    ): VfsResult<CreationOptions> {
        if (domainValue > Int.MAX_VALUE.toULong() || typeValue > Int.MAX_VALUE.toULong() ||
            protocolValue > Int.MAX_VALUE.toULong()
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val domain = SocketDomain.fromAbi(domainValue.toInt())
            ?: return VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        val raw = typeValue.toInt()
        if (raw and (SOCK_TYPE_MASK or SOCK_SUPPORTED_FLAGS).inv() != 0) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val type = SocketType.fromAbi(raw and SOCK_TYPE_MASK)
            ?: return VfsResult.Err(VfsError.SOCKET_TYPE_NOT_SUPPORTED)
        val requestedProtocol = protocolValue.toInt()
        val protocol = when (domain) {
            SocketDomain.UNIX -> {
                if (requestedProtocol != 0) {
                    return VfsResult.Err(VfsError.PROTOCOL_NOT_SUPPORTED)
                }
                if (type == SocketType.RAW) {
                    return VfsResult.Err(VfsError.SOCKET_TYPE_NOT_SUPPORTED)
                }
                0
            }
            SocketDomain.IPV4 -> when (type) {
                SocketType.STREAM -> if (requestedProtocol == 0 ||
                    requestedProtocol == IpProtocol.TCP.number.toInt()
                ) {
                    IpProtocol.TCP.number.toInt()
                } else {
                    return VfsResult.Err(VfsError.PROTOCOL_NOT_SUPPORTED)
                }
                SocketType.DATAGRAM -> when (requestedProtocol) {
                    0, IpProtocol.UDP.number.toInt() -> IpProtocol.UDP.number.toInt()
                    IpProtocol.ICMP.number.toInt() -> IpProtocol.ICMP.number.toInt()
                    else -> return VfsResult.Err(VfsError.PROTOCOL_NOT_SUPPORTED)
                }
                SocketType.RAW,
                SocketType.SEQUENCED_PACKET,
                -> return VfsResult.Err(VfsError.SOCKET_TYPE_NOT_SUPPORTED)
            }
            SocketDomain.NETLINK -> {
                if (type != SocketType.RAW && type != SocketType.DATAGRAM) {
                    return VfsResult.Err(VfsError.SOCKET_TYPE_NOT_SUPPORTED)
                }
                NetlinkProtocolKind.fromNumber(requestedProtocol)?.number
                    ?: return VfsResult.Err(VfsError.PROTOCOL_NOT_SUPPORTED)
            }
        }
        return VfsResult.Ok(
            CreationOptions(
                domain,
                type,
                protocol,
                nonBlocking = raw and SOCK_NONBLOCK != 0,
                descriptorFlags = if (raw and SOCK_CLOEXEC != 0) {
                    FileDescriptorFlags.FD_CLOEXEC
                } else {
                    0uL
                },
            ),
        )
    }

    private fun sendFlags(value: ULong): Int? = flags(value, SEND_FLAGS)

    private fun receiveFlags(value: ULong): Int? = flags(value, RECEIVE_FLAGS)

    private fun flags(value: ULong, supported: Int): Int? {
        if (value > Int.MAX_VALUE.toULong()) return null
        return value.toInt().takeIf { it and supported.inv() == 0 }
    }

    private fun isNonBlocking(file: OpenFileDescription, flags: Int): Boolean =
        flags and MSG_DONTWAIT != 0 || file.getStatusFlags() and OpenFlags.O_NONBLOCK != 0

    private fun credentials(process: Process): UnixCredentials = UnixCredentials(
        process.id,
        process.credentials.userIds.effective.toUInt(),
        process.credentials.groupIds.effective.toUInt(),
    )

    private fun intOption(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES).also { bytes ->
        LittleEndianBuffer(bytes).writeU32(0, value.toUInt())
    }

    private fun lingerOption(linger: SocketLinger): ByteArray =
        ByteArray(Int.SIZE_BYTES * 2).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, if (linger.enabled) 1u else 0u)
                writeU32(Int.SIZE_BYTES, linger.seconds.toUInt())
            }
        }

    private fun socketTimeout(bytes: ByteArray): VfsResult<ULong?> {
        if (bytes.size < Long.SIZE_BYTES * 2) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val input = LittleEndianBuffer(bytes)
        val seconds = input.readU64(0).toLong()
        val microseconds = input.readU64(Long.SIZE_BYTES).toLong()
        if (seconds < 0 || microseconds !in 0 until MICROSECONDS_PER_SECOND.toLong()) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (seconds == 0L && microseconds == 0L) return VfsResult.Ok(null)
        val wholeSeconds = seconds.toULong()
        val fraction = microseconds.toULong() * NANOSECONDS_PER_MICROSECOND
        val duration = if (wholeSeconds >
            (ULong.MAX_VALUE - fraction) / NANOSECONDS_PER_SECOND
        ) {
            ULong.MAX_VALUE
        } else {
            wholeSeconds * NANOSECONDS_PER_SECOND + fraction
        }
        return VfsResult.Ok(duration)
    }

    private fun timevalOption(timeoutNanos: ULong?): ByteArray =
        ByteArray(Long.SIZE_BYTES * 2).also { bytes ->
            val duration = timeoutNanos ?: 0uL
            LittleEndianBuffer(bytes).apply {
                writeU64(0, duration / NANOSECONDS_PER_SECOND)
                writeU64(
                    Long.SIZE_BYTES,
                    duration % NANOSECONDS_PER_SECOND / NANOSECONDS_PER_MICROSECOND,
                )
            }
        }

    private fun credentialOption(credentials: UnixCredentials): ByteArray =
        ByteArray(SocketConstants.CREDENTIAL_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU32(0, credentials.processId.toUInt())
                writeU32(4, credentials.userId)
                writeU32(8, credentials.groupId)
            }
        }

    private fun copySocketOption(
        process: Process,
        outputAddress: ULong,
        lengthAddress: ULong,
        value: ByteArray,
    ): Long {
        val lengthMemory = UserMemory(process.addressSpace, lengthAddress)
        val requested = lengthMemory.readUIntLE() ?: return errno(Errno.EFAULT)
        val copied = minOf(requested.toULong(), value.size.toULong()).toInt()
        val output = UserMemory(process.addressSpace, outputAddress)
        if (!lengthMemory.isWritable(UInt.SIZE_BYTES) || !output.isWritable(copied)) {
            return errno(Errno.EFAULT)
        }
        if (copied != 0 && !output.copyToUser(value, size = copied)) return errno(Errno.EFAULT)
        val actual = ByteArray(UInt.SIZE_BYTES)
        LittleEndianBuffer(actual).writeU32(0, copied.toUInt())
        return if (lengthMemory.copyToUser(actual)) 0L else errno(Errno.EFAULT)
    }

    private class ReceiveTimeout private constructor(
        private val memory: UserMemory,
        val deadline: SocketDeadline,
    ) {
        fun expired(): Boolean = deadline.expired()

        fun writeRemaining(): Boolean {
            val remaining = deadline.remainingNanos()
            return memory.copyToUser(
                TimeSpec.fromDurationNanos(remaining).toNativeBytes(),
            )
        }

        companion object {
            fun read(process: Process, address: ULong): VfsResult<ReceiveTimeout?> {
                if (address == 0uL) return VfsResult.Ok(null)
                if (!TscClock.isReady) return VfsResult.Err(VfsError.IO)
                val memory = UserMemory(process.addressSpace, address)
                val bytes = memory.copyFromUser(TimeSpec.NATIVE_SIZE)
                    ?: return VfsResult.Err(VfsError.FAULT)
                if (!memory.isWritable(TimeSpec.NATIVE_SIZE)) {
                    return VfsResult.Err(VfsError.FAULT)
                }
                val value = TimeSpec(0, 0)
                if (!value.updateFromNativeBytes(bytes) || !value.isValidDuration) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                return VfsResult.Ok(
                    ReceiveTimeout(
                        memory,
                        checkNotNull(SocketDeadline.after(value.durationNanos)),
                    ),
                )
            }
        }
    }

    private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
    private const val MICROSECONDS_PER_SECOND = 1_000_000uL
    private const val NANOSECONDS_PER_MICROSECOND = 1_000uL
    private const val MAX_SOCKET_OPTION_SIZE = 4096
}
