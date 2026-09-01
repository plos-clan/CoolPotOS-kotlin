package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.utils.PollEvents

internal class PidFd(
    val target: PidHandle,
) : AnonymousFileBackend(InodeType.PIDFD, "pidfd"), PositionlessOpenFileBackend {
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

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long {
        val available = when (target.state) {
            PidHandle.State.RUNNING -> 0
            PidHandle.State.EXITED -> PollEvents.NORMAL_INPUT
            PidHandle.State.DEAD -> PollEvents.NORMAL_INPUT or PollEvents.POLLHUP
        }
        return (available and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
    }
}
