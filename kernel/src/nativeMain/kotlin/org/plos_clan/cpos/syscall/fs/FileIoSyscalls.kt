@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.IoResult
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
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
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

internal fun copyFileRange(regs: PtraceRegisters, process: Process): Long {
    val rawFlags = regs[PtraceRegisters.IDX_R9]
    if (rawFlags > UInt.MAX_VALUE.toULong() || rawFlags != 0uL) {
        return errno(Errno.EINVAL)
    }
    val sourceDescriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val destinationDescriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDX])
        ?: return errno(Errno.EBADF)
    val sourceOffset = when (val result = userFileOffset(
        process,
        regs[PtraceRegisters.IDX_RSI],
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val destinationOffset = when (val result = userFileOffset(
        process,
        regs[PtraceRegisters.IDX_R10],
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    val source = process.fdTable.acquire(sourceDescriptor) ?: return errno(Errno.EBADF)
    val destination = process.fdTable.acquire(destinationDescriptor) ?: run {
        source.release()
        return errno(Errno.EBADF)
    }
    return try {
        val length = minOf(regs[PtraceRegisters.IDX_R8], MAX_RW_COUNT)
        when (val result = source.copyFileRange(
            process.vfsOperationContext,
            destination,
            sourceOffset?.value,
            destinationOffset?.value,
            length,
            rawFlags.toUInt(),
        )) {
            is VfsResult.Err -> errno(result.error.errno)
            is VfsResult.Ok -> {
                val copied = result.value
                if (sourceOffset?.write(copied) == false ||
                    destinationOffset?.write(copied) == false
                ) {
                    errno(Errno.EFAULT)
                } else {
                    copied.toLong()
                }
            }
        }
    } finally {
        destination.release()
        source.release()
    }
}

private class UserFileOffset(
    private val memory: UserMemory,
    val value: ULong,
) {
    fun write(delta: ULong): Boolean {
        val bytes = ByteArray(ULong.SIZE_BYTES)
        LittleEndianBuffer(bytes).writeU64(0, value + delta)
        return memory.copyToUser(bytes)
    }
}

private fun userFileOffset(process: Process, address: ULong): VfsResult<UserFileOffset?> {
    if (address == 0uL) return VfsResult.Ok(null)
    val memory = UserMemory(process.addressSpace, address)
    val bytes = memory.copyFromUser(ULong.SIZE_BYTES)
        ?: return VfsResult.Err(VfsError.FAULT)
    val value = LittleEndianBuffer(bytes).readU64(0)
    if (value > Long.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    if (!memory.isWritable(ULong.SIZE_BYTES)) return VfsResult.Err(VfsError.FAULT)
    return VfsResult.Ok(UserFileOffset(memory, value))
}

private object FileIo {
    fun scalar(
        direction: Direction,
        position: ULong?,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val count = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT).toInt()
        val buffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
        direction.transfer(process.vfsOperationContext, file, buffer, count, position).raw
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
        direction.transfer(process.vfsOperationContext, file, buffer, buffer.size, null).raw
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
            caller: VfsOperationContext,
            file: OpenFileDescription,
            buffer: IoBuffer,
            count: Int,
            position: ULong?,
        ): IoResult = when (this) {
            READ -> if (position == null) {
                file.read(caller, buffer, 0, count)
            } else {
                file.readAt(caller, position, buffer, 0, count)
            }

            WRITE -> if (position == null) {
                file.write(caller, buffer, 0, count)
            } else {
                file.writeAt(caller, position, buffer, 0, count)
            }
        }
    }
}
