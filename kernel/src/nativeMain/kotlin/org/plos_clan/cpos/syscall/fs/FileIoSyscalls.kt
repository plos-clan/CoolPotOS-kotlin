@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.mem.UserIoVector
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_IO_VECTORS
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_RW_COUNT
import org.plos_clan.cpos.syscall.fs.FsConstants.SPLICE_F_NONBLOCK
import org.plos_clan.cpos.syscall.fs.FsConstants.SPLICE_SUPPORTED_FLAGS
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal enum class FileTransfer(
    private val input: Int = PtraceRegisters.IDX_RDI,
    private val output: Int = PtraceRegisters.IDX_RDX,
    private val offset: Int = PtraceRegisters.IDX_RSI,
    private val size: Int = PtraceRegisters.IDX_R8,
) {
    SENDFILE(
        PtraceRegisters.IDX_RSI, PtraceRegisters.IDX_RDI,
        PtraceRegisters.IDX_RDX, PtraceRegisters.IDX_R10,
    ),
    SPLICE,
    COPY_FILE_RANGE;

    fun invoke(regs: PtraceRegisters, process: Process): Long {
        val sendFile = this == SENDFILE
        val rawFlags = if (sendFile) 0uL else regs[PtraceRegisters.IDX_R9]
        val allowed = if (this == SPLICE) SPLICE_SUPPORTED_FLAGS else 0u
        if (rawFlags and allowed.toULong().inv() != 0uL) return errno(Errno.EINVAL)
        val inputFd = fileDescriptor(regs[input]) ?: return errno(Errno.EBADF)
        val outputFd = fileDescriptor(regs[output]) ?: return errno(Errno.EBADF)
        val length = regs[size]
        if (sendFile && length.toLong() < 0) return errno(Errno.EINVAL)
        val count = minOf(length, MAX_RW_COUNT).toInt()
        val from = when (val result = userFileOffset(process, regs[offset])) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val targetOffset = if (sendFile) 0uL else regs[PtraceRegisters.IDX_R10]
        val to = when (val result = userFileOffset(process, targetOffset)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val source = process.fdTable.acquire(inputFd) ?: return errno(Errno.EBADF)
        val target = process.fdTable.acquire(outputFd) ?: run {
            source.release()
            return errno(Errno.EBADF)
        }
        return try {
            val caller = process.vfsOperationContext
            val transferred = transfer(caller, source, target, from, to, count, rawFlags.toUInt())
            if (transferred < 0) return transferred
            val bytes = transferred.toULong()
            if (from?.write(bytes) == false || to?.write(bytes) == false) return errno(Errno.EFAULT)
            transferred
        } catch (_: OutOfMemoryError) {
            errno(Errno.ENOMEM)
        } finally {
            target.release()
            source.release()
        }
    }

    private fun transfer(
        caller: VfsOperationContext,
        source: OpenFileDescription,
        target: OpenFileDescription,
        from: UserFileOffset?,
        to: UserFileOffset?,
        count: Int,
        flags: UInt,
    ): Long {
        val result = when (this) {
            SENDFILE -> source.sendFile(caller, target, from?.value, count)
            COPY_FILE_RANGE -> source.copyFileRange(
                caller, target, from?.value, to?.value, count.toULong(), flags,
            )
            SPLICE -> {
                val nonBlocking = flags and SPLICE_F_NONBLOCK != 0u
                val result = source.spliceTo(caller, target, from?.value, to?.value, count, nonBlocking)
                return result.raw
            }
        }
        return when (result) {
            is VfsResult.Ok -> result.value.toLong()
            is VfsResult.Err -> errno(result.error.errno)
        }
    }
}

private class UserFileOffset(
    private val memory: UserMemory,
    val value: ULong,
) {
    fun write(delta: ULong): Boolean = memory.writeULong(value + delta)
}

private fun userFileOffset(process: Process, address: ULong): VfsResult<UserFileOffset?> {
    if (address == 0uL) return VfsResult.Ok(null)
    val memory = UserMemory(process.addressSpace, address)
    val value = memory.readULongLE()
        ?: return VfsResult.Err(VfsError.FAULT)
    if (value > Long.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    if (!memory.isWritable(ULong.SIZE_BYTES)) return VfsResult.Err(VfsError.FAULT)
    return VfsResult.Ok(UserFileOffset(memory, value))
}

internal enum class FileIo(private val positioned: Boolean) {
    READ(false), WRITE(false), PREAD(true), PWRITE(true);

    fun scalar(regs: PtraceRegisters, process: Process): Long =
        withFile(regs, process) { file, position ->
            val count = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT).toInt()
            val buffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
            transfer(process.vfsOperationContext, file, buffer, count, position)
        }

    fun vector(regs: PtraceRegisters, process: Process): Long =
        withFile(regs, process) { file, position ->
            val count = regs[PtraceRegisters.IDX_RDX]
            if (count > MAX_IO_VECTORS.toULong()) return@withFile errno(Errno.EINVAL)
            val address = regs[PtraceRegisters.IDX_RSI]
            val imported = UserIoVector.fromUser(
                process.addressSpace, address, count.toInt(), MAX_RW_COUNT.toInt(),
            )
            val buffer = when (imported) {
                is VfsResult.Ok -> imported.value
                is VfsResult.Err -> return@withFile errno(imported.error.errno)
            }
            if (buffer.size == 0) return@withFile 0L
            transfer(process.vfsOperationContext, file, buffer, buffer.size, position)
        }

    private inline fun withFile(
        regs: PtraceRegisters,
        process: Process,
        operation: (OpenFileDescription, ULong) -> Long,
    ): Long {
        val position = if (positioned) regs[PtraceRegisters.IDX_R10] else 0uL
        if (position.toLong() < 0) return errno(Errno.EINVAL)
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            if (positioned && !file.supportsPositionedIo) return errno(Errno.ESPIPE)
            val allowed = when (this) {
                READ, PREAD -> file.access.canRead
                WRITE, PWRITE -> file.access.canWrite
            }
            if (!allowed) return errno(Errno.EBADF)
            operation(file, position)
        } finally {
            file.release()
        }
    }

    private fun transfer(
        caller: VfsOperationContext,
        file: OpenFileDescription,
        buffer: IoBuffer,
        count: Int,
        position: ULong,
    ): Long {
        if (count.toLong() > Long.MAX_VALUE - position.toLong()) return errno(Errno.EINVAL)
        return when (this) {
            READ -> file.read(caller, buffer, 0, count)
            WRITE -> file.write(caller, buffer, 0, count)
            PREAD -> file.readAt(caller, position, buffer, 0, count)
            PWRITE -> file.writeAt(caller, position, buffer, 0, count)
        }.raw
    }
}
