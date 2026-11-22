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
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun splice(regs: PtraceRegisters, process: Process): Long {
    val rawFlags = regs[PtraceRegisters.IDX_R9]
    if (rawFlags > UInt.MAX_VALUE.toULong() ||
        rawFlags.toUInt() and SPLICE_SUPPORTED_FLAGS.inv() != 0u
    ) {
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
        val count = minOf(regs[PtraceRegisters.IDX_R8], MAX_RW_COUNT).toInt()
        val result = try {
            source.spliceTo(
                process.vfsOperationContext,
                destination,
                sourceOffset?.value,
                destinationOffset?.value,
                count,
                nonBlocking = rawFlags.toUInt() and SPLICE_F_NONBLOCK != 0u,
            )
        } catch (_: OutOfMemoryError) {
            return errno(Errno.ENOMEM)
        }
        if (!result.isSuccess) return result.raw

        val transferred = result.bytesTransferred.toULong()
        if (sourceOffset?.write(transferred) == false ||
            destinationOffset?.write(transferred) == false
        ) errno(Errno.EFAULT) else result.raw
    } finally {
        destination.release()
        source.release()
    }
}

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
