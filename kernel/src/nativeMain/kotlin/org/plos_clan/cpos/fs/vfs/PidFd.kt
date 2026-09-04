package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal class PidFd(
    val target: PidHandle,
) : AnonymousFileBackend(InodeType.PIDFD, "pidfd"), PositionlessOpenFileBackend {
    override val fileSystemMagic: ULong
        get() = PID_FS_MAGIC

    override val seekable: Boolean
        get() = false

    override val readinessVersion: Int
        get() = target.state.ordinal

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.INVALID_ARGUMENT)

    override fun write(
        caller: VfsOperationContext,
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult = IoResult.failure(VfsError.INVALID_ARGUMENT)

    override fun ioctl(
        caller: VfsOperationContext,
        inode: Inode,
        command: Int,
        args: UserMemory,
    ): Long {
        val request = command.toUInt()
        if (request and IOCTL_OPERATION_MASK != GET_INFO_OPERATION) {
            return -VfsError.NOT_TTY.errno.toLong()
        }
        val size = (request shr IOCTL_SIZE_SHIFT and IOCTL_SIZE_MASK).toInt()
        if (size < INFO_V0_SIZE) return -VfsError.INVALID_ARGUMENT.errno.toLong()
        if (target.state == PidHandle.State.DEAD) {
            return -VfsError.NO_SUCH_PROCESS.errno.toLong()
        }

        val bytes = args.copyFromUser(size) ?: return -VfsError.FAULT.errno.toLong()
        bytes.fill(0)
        val process = target.thread.process
        val userIds = process.credentials.userIds
        val groupIds = process.credentials.groupIds
        LittleEndianBuffer(bytes).apply {
            writeU64(INFO_MASK_OFFSET, (INFO_PID or INFO_CREDS).toULong())
            writeU32(INFO_PID_OFFSET, target.thread.id.toUInt())
            writeU32(INFO_TGID_OFFSET, process.id.toUInt())
            writeU32(INFO_PPID_OFFSET, process.parentId.toUInt())
            writeU32(INFO_RUID_OFFSET, userIds.real.toUInt())
            writeU32(INFO_RGID_OFFSET, groupIds.real.toUInt())
            writeU32(INFO_EUID_OFFSET, userIds.effective.toUInt())
            writeU32(INFO_EGID_OFFSET, groupIds.effective.toUInt())
            writeU32(INFO_SUID_OFFSET, userIds.saved.toUInt())
            writeU32(INFO_SGID_OFFSET, groupIds.saved.toUInt())
            writeU32(INFO_FSUID_OFFSET, userIds.filesystem.toUInt())
            writeU32(INFO_FSGID_OFFSET, groupIds.filesystem.toUInt())
        }
        return if (args.copyToUser(bytes)) 0L else -VfsError.FAULT.errno.toLong()
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long {
        val available = when (target.state) {
            PidHandle.State.RUNNING -> 0
            PidHandle.State.EXITED -> PollEvents.NORMAL_INPUT
            PidHandle.State.DEAD -> PollEvents.NORMAL_INPUT or PollEvents.POLLHUP
        }
        return (available and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
    }

    private companion object {
        const val PID_FS_MAGIC = 0x5049_4446uL
        const val IOCTL_OPERATION_MASK = 0xc000_ffffu
        const val GET_INFO_OPERATION = 0xc000_ff0bu
        const val IOCTL_SIZE_SHIFT = 16
        const val IOCTL_SIZE_MASK = 0x3fffu

        const val INFO_V0_SIZE = 64
        const val INFO_PID = 1u
        const val INFO_CREDS = 2u
        const val INFO_MASK_OFFSET = 0
        const val INFO_PID_OFFSET = 16
        const val INFO_TGID_OFFSET = 20
        const val INFO_PPID_OFFSET = 24
        const val INFO_RUID_OFFSET = 28
        const val INFO_RGID_OFFSET = 32
        const val INFO_EUID_OFFSET = 36
        const val INFO_EGID_OFFSET = 40
        const val INFO_SUID_OFFSET = 44
        const val INFO_SGID_OFFSET = 48
        const val INFO_FSUID_OFFSET = 52
        const val INFO_FSGID_OFFSET = 56
    }
}
