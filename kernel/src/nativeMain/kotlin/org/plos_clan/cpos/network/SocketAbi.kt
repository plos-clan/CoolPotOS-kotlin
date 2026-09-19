package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketControlMessage
import org.plos_clan.cpos.fs.sock.SocketReceiveResult
import org.plos_clan.cpos.fs.sock.UnixAncillaryData
import org.plos_clan.cpos.fs.sock.UnixCredentials
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.fs.FsConstants
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class SocketAddressMemory private constructor(
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
            return SocketAddressAbi.decode(bytes, allowUnspec)
        }

        fun prepare(
            process: Process,
            address: ULong,
            lengthAddress: ULong,
            optional: Boolean,
        ): VfsResult<SocketAddressMemory> {
            if (address == 0uL && lengthAddress == 0uL && optional) {
                return VfsResult.Ok(SocketAddressMemory(null, 0, null))
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
            return VfsResult.Ok(SocketAddressMemory(memory, capacity, lengthMemory))
        }

        fun prepareMessage(
            process: Process,
            address: ULong,
            capacity: UInt,
        ): VfsResult<SocketAddressMemory> {
            if (address == 0uL) return VfsResult.Ok(SocketAddressMemory(null, 0, null))
            val writable = minOf(capacity.toULong(), SocketConstants.SOCKET_ADDRESS_SIZE.toULong())
                .toInt()
            val memory = UserMemory(process.addressSpace, address)
            if (!memory.isWritable(writable)) return VfsResult.Err(VfsError.FAULT)
            val length = capacity.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()
            return VfsResult.Ok(SocketAddressMemory(memory, length, null))
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
            val header = UserMessageHeader(
                memory,
                input.readU64(0),
                input.readU32(8),
                input.readU64(16),
                vectorCount.toInt(),
                input.readU64(32),
                controlLength.toInt(),
            )
            return VfsResult.Ok(header)
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
        receiveTimestamp: Boolean,
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

        val timestamp = if (receiveTimestamp &&
            (result.copiedBytes != 0 || result.endOfRecord)
        ) {
            result.receivedAtNanos?.let(RealtimeClock::atMonotonic) ?: RealtimeClock.now()
        } else {
            null
        }
        val messages = if (credentials == null && timestamp == null) {
            result.controlMessages
        } else {
            ArrayList<SocketControlMessage>(result.controlMessages.size + 2).apply {
                addAll(result.controlMessages)
                timestamp?.let { receivedAt ->
                    add(
                        SocketControlMessage.Longs(
                            SocketConstants.SOL_SOCKET,
                            SocketConstants.SCM_TIMESTAMP,
                            longArrayOf(
                                receivedAt.seconds,
                                (receivedAt.nanoseconds / NANOSECONDS_PER_MICROSECOND).toLong(),
                            ),
                        ),
                    )
                }
                credentials?.let { sender ->
                    add(
                        SocketControlMessage.Integers(
                            SocketConstants.SOL_SOCKET,
                            SocketConstants.SCM_CREDENTIALS,
                            intArrayOf(
                                sender.processId,
                                sender.userId.toInt(),
                                sender.groupId.toInt(),
                            ),
                        ),
                    )
                }
            }
        }
        val originalRights = ancillary?.fileCount ?: 0
        val required = messages.sumOf(SocketControlMessage::space) +
            (if (originalRights == 0) 0 else SocketControlMessage.space(originalRights * Int.SIZE_BYTES))
        val output = ByteArray(minOf(capacity, required))
        val writableCapacity = output.size
        var used = 0
        var truncated = false
        var installed = IntArray(0)
        for (message in messages) {
            if (message.length > writableCapacity - used) truncated = true
            used += message.writeTo(output, used)
        }
        val availableForRights = writableCapacity - used
        val rightsCapacity = (availableForRights - SocketConstants.CONTROL_HEADER_SIZE)
            .coerceAtLeast(0) / Int.SIZE_BYTES
        val requestedRights = minOf(originalRights, rightsCapacity)
        if (requestedRights != 0) {
            val files = checkNotNull(ancillary).takeFiles(requestedRights)
            val descriptorFlags = if (closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
            val descriptors = process.fdTable.installAvailable(files, descriptorFlags)
            installed = descriptors
            for (index in descriptors.size until files.size) files[index].release()
            if (descriptors.isNotEmpty()) {
                val rights = SocketControlMessage.Integers(
                    SocketConstants.SOL_SOCKET,
                    SocketConstants.SCM_RIGHTS,
                    descriptors,
                )
                used += rights.writeTo(output, used)
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
        val processCredentials = process.credentials
        if (processCredentials.userIds.effective == 0) return true
        val userId = credentials.userId.toInt()
        val groupId = credentials.groupId.toInt()
        val validUser = userId == processCredentials.userIds.real ||
            userId == processCredentials.userIds.effective || userId == processCredentials.userIds.saved
        val validGroup = groupId == processCredentials.groupIds.real ||
            groupId == processCredentials.groupIds.effective || groupId == processCredentials.groupIds.saved
        return credentials.processId == process.id && validUser && validGroup
    }

    private fun alignControl(length: ULong): ULong =
        (length + ULong.SIZE_BYTES.toULong() - 1uL) and
            (ULong.SIZE_BYTES.toULong() - 1uL).inv()

    private const val NANOSECONDS_PER_MICROSECOND = 1_000u
}
