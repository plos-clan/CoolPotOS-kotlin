package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.UnspecifiedSocketAddress
import org.plos_clan.cpos.fs.sock.UnixAncillaryData
import org.plos_clan.cpos.fs.sock.UnixCredentials
import org.plos_clan.cpos.fs.sock.UnixSocketAddress
import org.plos_clan.cpos.fs.sock.UnixSocketName
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.fs.FsConstants
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal object SocketConstants {
    const val AF_UNSPEC = 0
    const val AF_UNIX = 1
    const val AF_INET = 2
    const val AF_NETLINK = 16

    const val SOCK_TYPE_MASK = 0xF
    const val SOCK_NONBLOCK = 0x800
    const val SOCK_CLOEXEC = 0x8_0000
    const val SOCK_SUPPORTED_FLAGS = SOCK_NONBLOCK or SOCK_CLOEXEC

    const val SOL_SOCKET = 1
    const val SCM_RIGHTS = 1
    const val SCM_CREDENTIALS = 2

    const val SO_REUSEADDR = 2
    const val SO_TYPE = 3
    const val SO_ERROR = 4
    const val SO_BROADCAST = 6
    const val SO_SNDBUF = 7
    const val SO_RCVBUF = 8
    const val SO_KEEPALIVE = 9
    const val SO_LINGER = 13
    const val SO_PASSCRED = 16
    const val SO_PEERCRED = 17
    const val SO_RCVLOWAT = 18
    const val SO_SNDLOWAT = 19
    const val SO_RCVTIMEO = 20
    const val SO_SNDTIMEO = 21
    const val SO_ACCEPTCONN = 30
    const val SO_PROTOCOL = 38
    const val SO_DOMAIN = 39

    const val MSG_PEEK = 0x0002
    const val MSG_DONTROUTE = 0x0004
    const val MSG_CTRUNC = 0x0008
    const val MSG_TRUNC = 0x0020
    const val MSG_DONTWAIT = 0x0040
    const val MSG_EOR = 0x0080
    const val MSG_WAITALL = 0x0100
    const val MSG_NOSIGNAL = 0x4000
    const val MSG_MORE = 0x8000
    const val MSG_WAITFORONE = 0x1_0000
    const val MSG_CMSG_CLOEXEC = 0x4000_0000

    const val SEND_FLAGS = MSG_DONTROUTE or MSG_DONTWAIT or MSG_EOR or MSG_NOSIGNAL or MSG_MORE
    const val RECEIVE_FLAGS = MSG_PEEK or MSG_TRUNC or MSG_DONTWAIT or MSG_WAITALL or
        MSG_CMSG_CLOEXEC or MSG_WAITFORONE

    const val SOCKET_ADDRESS_SIZE = 128
    const val UNIX_ADDRESS_SIZE = 110
    const val SOCKET_PATH_SIZE = UNIX_ADDRESS_SIZE - UShort.SIZE_BYTES
    const val MESSAGE_HEADER_SIZE = 56
    const val MULTI_MESSAGE_HEADER_SIZE = 64
    const val CONTROL_HEADER_SIZE = 16
    const val CREDENTIAL_SIZE = Int.SIZE_BYTES * 3
    const val MAX_CONTROL_SIZE = 1024 * 1024
    const val MAX_RIGHTS = 253
}

internal object SocketAddressAbi {
    fun read(
        process: Process,
        address: ULong,
        length: ULong,
        allowUnspec: Boolean = false,
    ): VfsResult<SocketAddress> {
        if (length < UShort.SIZE_BYTES.toULong() ||
            length > SocketConstants.SOCKET_ADDRESS_SIZE.toULong()
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val bytes = UserMemory(process.addressSpace, address).copyFromUser(length.toInt())
            ?: return VfsResult.Err(VfsError.FAULT)
        return decode(bytes, allowUnspec)
    }

    fun decode(bytes: ByteArray, allowUnspec: Boolean = false): VfsResult<SocketAddress> {
        if (bytes.size !in UShort.SIZE_BYTES..SocketConstants.SOCKET_ADDRESS_SIZE) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val family = LittleEndianBuffer(bytes).readU16(0).toInt()
        if (family == SocketConstants.AF_UNSPEC && allowUnspec) {
            return VfsResult.Ok(UnspecifiedSocketAddress)
        }
        return when (family) {
            SocketConstants.AF_UNIX -> decodeUnix(bytes)
            SocketConstants.AF_INET -> decodeIpv4(bytes)
            SocketConstants.AF_NETLINK -> decodeNetlink(bytes)
            else -> VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
    }

    fun encode(address: SocketAddress): ByteArray = when (address) {
        is UnixSocketAddress -> encodeUnix(address)
        is Ipv4SocketAddress -> ByteArray(IPV4_ADDRESS_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_INET.toUShort())
            NetworkOrderBuffer(bytes).apply {
                writeU16(2, address.port)
                writeU32(4, address.address.value)
            }
        }
        is NetlinkSocketAddress -> ByteArray(NETLINK_ADDRESS_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU16(0, SocketConstants.AF_NETLINK.toUShort())
                writeU16(2, 0u)
                writeU32(4, address.portId)
                writeU32(8, address.groups)
            }
        }
        UnspecifiedSocketAddress -> ByteArray(UShort.SIZE_BYTES)
        else -> error("Unsupported socket address ${address::class.simpleName}")
    }

    private fun decodeUnix(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size > SocketConstants.UNIX_ADDRESS_SIZE) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val path = bytes.copyOfRange(UShort.SIZE_BYTES, bytes.size)
        if (path.isEmpty()) return VfsResult.Ok(UnixSocketAddress.Unnamed)
        if (path[0] == 0.toByte()) {
            return VfsResult.Ok(
                UnixSocketAddress.Abstract(
                    UnixSocketName.fromBytes(path.copyOfRange(1, path.size)),
                ),
            )
        }
        val terminator = path.indexOf(0)
        val length = if (terminator < 0) path.size else terminator
        if (length == 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(
            UnixSocketAddress.Pathname(VfsPathname.fromBytes(path.copyOf(length))),
        )
    }

    private fun decodeIpv4(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size < IPV4_ADDRESS_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = NetworkOrderBuffer(bytes)
        return VfsResult.Ok(
            Ipv4SocketAddress(
                Ipv4Address.fromBits(input.readU32(4)),
                input.readU16(2),
            ),
        )
    }

    private fun decodeNetlink(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size < NETLINK_ADDRESS_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(bytes)
        return VfsResult.Ok(NetlinkSocketAddress(input.readU32(4), input.readU32(8)))
    }

    private fun encodeUnix(address: UnixSocketAddress): ByteArray {
        val path = when (address) {
            UnixSocketAddress.Unnamed -> ByteArray(0)
            is UnixSocketAddress.Abstract -> byteArrayOf(0) + address.name.copyBytes()
            is UnixSocketAddress.Pathname -> address.pathname.copyBytes().let { bytes ->
                if (bytes.size < SocketConstants.SOCKET_PATH_SIZE) bytes + byteArrayOf(0) else bytes
            }
        }
        return ByteArray(UShort.SIZE_BYTES + path.size).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_UNIX.toUShort())
            path.copyInto(bytes, UShort.SIZE_BYTES)
        }
    }

    private const val IPV4_ADDRESS_SIZE = 16
    private const val NETLINK_ADDRESS_SIZE = 12
}

internal class SocketAddressOutput private constructor(
    private val memory: UserMemory?,
    private val capacity: Int,
    private val lengthMemory: UserMemory?,
) {
    fun write(address: SocketAddress): Boolean {
        if (memory == null) return true
        val encoded = SocketAddressAbi.encode(address)
        val copied = minOf(capacity, encoded.size)
        if (copied != 0 && !memory.copyToUser(encoded, size = copied)) return false
        val length = ByteArray(UInt.SIZE_BYTES)
        LittleEndianBuffer(length).writeU32(0, encoded.size.toUInt())
        return checkNotNull(lengthMemory).copyToUser(length)
    }

    companion object {
        fun prepare(
            process: Process,
            address: ULong,
            lengthAddress: ULong,
            optional: Boolean,
        ): VfsResult<SocketAddressOutput> {
            if (address == 0uL && lengthAddress == 0uL && optional) {
                return VfsResult.Ok(SocketAddressOutput(null, 0, null))
            }
            if (address == 0uL || lengthAddress == 0uL) {
                return VfsResult.Err(VfsError.FAULT)
            }
            val lengthMemory = UserMemory(process.addressSpace, lengthAddress)
            val capacity = lengthMemory.readUIntLE()
                ?.coerceAtMost(Int.MAX_VALUE.toUInt())
                ?.toInt()
                ?: return VfsResult.Err(VfsError.FAULT)
            val memory = UserMemory(process.addressSpace, address)
            if (!lengthMemory.isWritable(UInt.SIZE_BYTES) ||
                !memory.isWritable(minOf(capacity, SocketConstants.SOCKET_ADDRESS_SIZE))
            ) {
                return VfsResult.Err(VfsError.FAULT)
            }
            return VfsResult.Ok(SocketAddressOutput(memory, capacity, lengthMemory))
        }

        fun prepareMessage(
            process: Process,
            address: ULong,
            capacity: UInt,
        ): VfsResult<SocketAddressOutput> {
            if (address == 0uL) return VfsResult.Ok(SocketAddressOutput(null, 0, null))
            val writable = minOf(capacity.toULong(), SocketConstants.SOCKET_ADDRESS_SIZE.toULong())
                .toInt()
            val memory = UserMemory(process.addressSpace, address)
            if (!memory.isWritable(writable)) return VfsResult.Err(VfsError.FAULT)
            return VfsResult.Ok(
                SocketAddressOutput(
                    memory,
                    capacity.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt(),
                    null,
                ),
            )
        }
    }

    fun writeMessage(address: SocketAddress): VfsResult<UInt> {
        if (memory == null) return VfsResult.Ok(0u)
        val encoded = SocketAddressAbi.encode(address)
        val copied = minOf(capacity, encoded.size)
        if (copied != 0 && !memory.copyToUser(encoded, size = copied)) {
            return VfsResult.Err(VfsError.FAULT)
        }
        return VfsResult.Ok(encoded.size.toUInt())
    }
}

internal data class UserMessageHeader(
    val memory: UserMemory,
    val nameAddress: ULong,
    val nameLength: UInt,
    val vectorAddress: ULong,
    val vectorCount: Int,
    val controlAddress: ULong,
    val controlLength: Int,
) {
    fun vector(process: Process): UserIoVector? = UserIoVector.fromUser(
        process.addressSpace,
        vectorAddress,
        vectorCount,
        FsConstants.MAX_RW_COUNT.toInt(),
    )

    fun writeResult(nameLength: UInt, controlLength: Int, flags: Int): Boolean {
        val bytes = memory.copyFromUser(SocketConstants.MESSAGE_HEADER_SIZE) ?: return false
        LittleEndianBuffer(bytes).apply {
            writeU32(8, nameLength)
            writeU64(40, controlLength.toULong())
            writeU32(48, flags.toUInt())
        }
        return memory.copyToUser(bytes)
    }

    companion object {
        fun read(process: Process, address: ULong, writable: Boolean): VfsResult<UserMessageHeader> {
            val memory = UserMemory(process.addressSpace, address)
            val bytes = memory.copyFromUser(SocketConstants.MESSAGE_HEADER_SIZE)
                ?: return VfsResult.Err(VfsError.FAULT)
            if (writable && !memory.isWritable(SocketConstants.MESSAGE_HEADER_SIZE)) {
                return VfsResult.Err(VfsError.FAULT)
            }
            val input = LittleEndianBuffer(bytes)
            val vectorCount = input.readU64(24)
            val controlLength = input.readU64(40)
            if (vectorCount > FsConstants.MAX_IO_VECTORS.toULong() ||
                controlLength > SocketConstants.MAX_CONTROL_SIZE.toULong()
            ) {
                return VfsResult.Err(VfsError.MESSAGE_TOO_LONG)
            }
            return VfsResult.Ok(
                UserMessageHeader(
                    memory,
                    input.readU64(0),
                    input.readU32(8),
                    input.readU64(16),
                    vectorCount.toInt(),
                    input.readU64(32),
                    controlLength.toInt(),
                ),
            )
        }
    }
}

internal object SocketControlMessages {
    fun read(process: Process, address: ULong, length: Int): VfsResult<UnixAncillaryData> {
        if (length == 0) return VfsResult.Ok(UnixAncillaryData())
        if (address == 0uL) return VfsResult.Err(VfsError.FAULT)
        val bytes = UserMemory(process.addressSpace, address).copyFromUser(length)
            ?: return VfsResult.Err(VfsError.FAULT)
        val input = LittleEndianBuffer(bytes)
        val files = mutableListOf<OpenFileDescription>()
        var credentials: UnixCredentials? = null
        var offset = 0
        while (offset <= bytes.size - SocketConstants.CONTROL_HEADER_SIZE) {
            val messageLength = input.readU64(offset)
            if (messageLength < SocketConstants.CONTROL_HEADER_SIZE.toULong() ||
                messageLength > (bytes.size - offset).toULong()
            ) {
                files.forEach { it.release() }
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val level = input.readU32(offset + ULong.SIZE_BYTES).toInt()
            val type = input.readU32(offset + ULong.SIZE_BYTES + Int.SIZE_BYTES).toInt()
            val payloadOffset = offset + SocketConstants.CONTROL_HEADER_SIZE
            val payloadLength = messageLength.toInt() - SocketConstants.CONTROL_HEADER_SIZE
            when {
                level != SocketConstants.SOL_SOCKET -> {
                    files.forEach { it.release() }
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                type == SocketConstants.SCM_RIGHTS -> {
                    if (payloadLength == 0 || payloadLength % Int.SIZE_BYTES != 0 ||
                        files.size + payloadLength / Int.SIZE_BYTES > SocketConstants.MAX_RIGHTS
                    ) {
                        files.forEach { it.release() }
                        return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    }
                    repeat(payloadLength / Int.SIZE_BYTES) { index ->
                        val fd = input.readU32(payloadOffset + index * Int.SIZE_BYTES).toInt()
                        val file = process.fdTable.acquire(fd)
                        if (file == null) {
                            files.forEach { it.release() }
                            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
                        }
                        files += file
                    }
                }
                type == SocketConstants.SCM_CREDENTIALS -> {
                    if (payloadLength < SocketConstants.CREDENTIAL_SIZE || credentials != null) {
                        files.forEach { it.release() }
                        return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    }
                    val supplied = UnixCredentials(
                        input.readU32(payloadOffset).toInt(),
                        input.readU32(payloadOffset + Int.SIZE_BYTES),
                        input.readU32(payloadOffset + Int.SIZE_BYTES * 2),
                    )
                    if (!credentialsAllowed(process, supplied)) {
                        files.forEach { it.release() }
                        return VfsResult.Err(VfsError.NOT_PERMITTED)
                    }
                    credentials = supplied
                }
                else -> {
                    files.forEach { it.release() }
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
            }
            val next = alignControl(messageLength)
            if (next > (bytes.size - offset).toULong()) break
            offset += next.toInt()
        }
        return VfsResult.Ok(UnixAncillaryData(files, credentials))
    }

    fun write(
        process: Process,
        address: ULong,
        capacity: Int,
        result: SocketReceiveResult,
        passCredentials: Boolean,
        closeOnExec: Boolean,
    ): VfsResult<ControlWriteResult> {
        val ancillary = result.ancillary
        val credentials = if (passCredentials) {
            ancillary?.credentials ?: result.senderCredentials
        } else {
            null
        }
        if (capacity != 0 && address == 0uL) {
            ancillary?.release()
            return VfsResult.Err(VfsError.FAULT)
        }
        val memory = UserMemory(process.addressSpace, address)

        val originalRights = ancillary?.fileCount ?: 0
        val required = (if (credentials == null) 0 else controlSpace(SocketConstants.CREDENTIAL_SIZE)) +
            (if (originalRights == 0) 0 else controlSpace(originalRights * Int.SIZE_BYTES))
        val output = ByteArray(minOf(capacity, required))
        val writableCapacity = output.size
        val writer = LittleEndianBuffer(output)
        var used = 0
        var truncated = false
        var installed = IntArray(0)
        if (credentials != null) {
            val space = controlSpace(SocketConstants.CREDENTIAL_SIZE)
            if (space <= writableCapacity) {
                writer.writeU64(used, (SocketConstants.CONTROL_HEADER_SIZE +
                    SocketConstants.CREDENTIAL_SIZE).toULong())
                writer.writeU32(used + 8, SocketConstants.SOL_SOCKET.toUInt())
                writer.writeU32(used + 12, SocketConstants.SCM_CREDENTIALS.toUInt())
                writer.writeU32(used + 16, credentials.processId.toUInt())
                writer.writeU32(used + 20, credentials.userId)
                writer.writeU32(used + 24, credentials.groupId)
                used += space
            } else {
                truncated = true
            }
        }

        val availableForRights = writableCapacity - used
        var rightsCapacity = if (availableForRights < SocketConstants.CONTROL_HEADER_SIZE +
            Int.SIZE_BYTES
        ) 0 else (availableForRights - SocketConstants.CONTROL_HEADER_SIZE) / Int.SIZE_BYTES
        while (rightsCapacity != 0 &&
            controlSpace(rightsCapacity * Int.SIZE_BYTES) > availableForRights
        ) {
            rightsCapacity--
        }
        val requestedRights = minOf(originalRights, rightsCapacity)
        if (requestedRights != 0) {
            val files = checkNotNull(ancillary).takeFiles(requestedRights)
            val descriptorFlags = if (closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
            val descriptors = process.fdTable.installAvailable(files, descriptorFlags)
            installed = descriptors
            for (index in descriptors.size until files.size) files[index].release()
            if (descriptors.isNotEmpty()) {
                val payloadLength = descriptors.size * Int.SIZE_BYTES
                writer.writeU64(
                    used,
                    (SocketConstants.CONTROL_HEADER_SIZE + payloadLength).toULong(),
                )
                writer.writeU32(used + 8, SocketConstants.SOL_SOCKET.toUInt())
                writer.writeU32(used + 12, SocketConstants.SCM_RIGHTS.toUInt())
                descriptors.forEachIndexed { index, fd ->
                    writer.writeU32(used + 16 + index * Int.SIZE_BYTES, fd.toUInt())
                }
                used += controlSpace(payloadLength)
            }
            if (descriptors.size != requestedRights) truncated = true
        }
        if (installed.size < originalRights) truncated = true
        ancillary?.release()
        if (used != 0 && !memory.copyToUser(output, size = used)) {
            installed.forEach { descriptor ->
                process.fdTable.close(process.vfsOperationContext, descriptor)
            }
            return VfsResult.Err(VfsError.FAULT)
        }
        return VfsResult.Ok(ControlWriteResult(used, truncated, installed))
    }

    data class ControlWriteResult(
        val length: Int,
        val truncated: Boolean,
        val installedDescriptors: IntArray,
    )

    private fun credentialsAllowed(process: Process, credentials: UnixCredentials): Boolean {
        if (process.euid == 0) return true
        val userId = credentials.userId.toInt()
        val groupId = credentials.groupId.toInt()
        val validUser = userId == process.ruid || userId == process.euid || userId == process.suid
        val validGroup = groupId == process.rgid || groupId == process.egid ||
            groupId == process.sgid
        return credentials.processId == process.id && validUser && validGroup
    }

    private fun alignControl(length: ULong): ULong =
        (length + ULong.SIZE_BYTES.toULong() - 1uL) and
            (ULong.SIZE_BYTES.toULong() - 1uL).inv()

    private fun controlSpace(payloadLength: Int): Int =
        alignControl((SocketConstants.CONTROL_HEADER_SIZE + payloadLength).toULong()).toInt()
}
