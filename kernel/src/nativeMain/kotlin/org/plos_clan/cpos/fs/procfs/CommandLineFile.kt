package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessState

internal class CommandLineFile(
    private val process: Process,
) : RegularFileBackend(), OpenFileBackend {
    override fun open(
        caller: VfsOperationContext,
        inode: Inode,
        options: OpenOptions,
    ): VfsResult<OpenFileBackend> = if (options.access.canWrite) {
        VfsResult.Err(VfsError.PERMISSION_DENIED)
    } else {
        VfsResult.Ok(this)
    }

    override fun read(
        caller: VfsOperationContext,
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (process.state == ProcessState.DEAD) return IoResult.failure(VfsError.NO_SUCH_PROCESS)
        if (process.state == ProcessState.ZOMBIE || count == 0) return IoResult.success(0)
        val space = process.addressSpace
        if (!space.retain()) return IoResult.success(0)
        val bytes = try {
            space.arguments.read(space, position.value, count)
        } finally {
            space.release()
        }
        if (bytes.isEmpty()) return IoResult.success(0)
        val copied = destination.copyFrom(destinationOffset, bytes, 0, bytes.size)
        if (copied == 0) return IoResult.failure(VfsError.FAULT)
        position.value += copied
        return IoResult.success(copied)
    }
}
