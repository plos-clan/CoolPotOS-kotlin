@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_IO_VECTORS
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_RW_COUNT
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun read(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.READ, null, regs, process)

internal fun write(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.WRITE, null, regs, process)

internal fun pread64(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.READ, regs[PtraceRegisters.IDX_R10], regs, process)

internal fun pwrite64(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.WRITE, regs[PtraceRegisters.IDX_R10], regs, process)

internal fun readv(regs: PtraceRegisters, process: Process): Long =
    FileIo.vector(FileIo.Direction.READ, regs, process)

internal fun writev(regs: PtraceRegisters, process: Process): Long =
    FileIo.vector(FileIo.Direction.WRITE, regs, process)

private object FileIo {
    fun scalar(
        direction: Direction,
        position: ULong?,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val count = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT).toInt()
        val buffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
        direction.transfer(file, buffer, count, position).raw
    }

    fun vector(
        direction: Direction,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val vectorCount = regs[PtraceRegisters.IDX_RDX]
        if (vectorCount > MAX_IO_VECTORS.toULong()) return@withFile errno(Errno.EINVAL)
        val buffer = UserIoVector.fromUser(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
            vectorCount.toInt(),
            MAX_RW_COUNT.toInt(),
        ) ?: return@withFile errno(Errno.EFAULT)
        direction.transfer(file, buffer, buffer.size, null).raw
    }

    private inline fun withFile(
        regs: PtraceRegisters,
        process: Process,
        operation: (OpenFileDescription) -> Long,
    ): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            operation(file)
        } finally {
            file.release()
        }
    }

    enum class Direction {
        READ,
        WRITE;

        fun transfer(
            file: OpenFileDescription,
            buffer: IoBuffer,
            count: Int,
            position: ULong?,
        ): IoResult = when (this) {
            READ -> if (position == null) {
                file.read(buffer, 0, count)
            } else {
                file.readAt(position, buffer, 0, count)
            }

            WRITE -> if (position == null) {
                file.write(buffer, 0, count)
            } else {
                file.writeAt(position, buffer, 0, count)
            }
        }
    }
}
