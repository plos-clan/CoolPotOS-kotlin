package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.FileLock
import org.plos_clan.cpos.fs.vfs.FileLockDomain
import org.plos_clan.cpos.fs.vfs.FileLockMode
import org.plos_clan.cpos.fs.vfs.FileLockRange
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal object RecordLock {
    fun execute(process: Process, descriptor: Int, command: Int, address: ULong): Long {
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            if (file.access == AccessMode.PATH) return errno(Errno.EBADF)
            val memory = UserMemory(process.addressSpace, address)
            val bytes = memory.copyFromUser(32) ?: return errno(Errno.EFAULT)
            val input = LittleEndianBuffer(bytes)
            val type = input.readU16(0).toInt()
            if (type !in 0..2 || input.readU32(24) != 0u) return errno(Errno.EINVAL)
            val mode = when (type) {
                0 -> FileLockMode.SHARED
                1 -> FileLockMode.EXCLUSIVE
                else -> null
            }
            val base = when (input.readU16(2).toInt()) {
                0 -> 0L
                1 -> file.offset
                2 -> when (val result = file.inode.attributes(process.vfsOperationContext)) {
                    is VfsResult.Ok -> {
                        val size = result.value.metadata.size
                        if (size > Long.MAX_VALUE.toULong()) return errno(Errno.EOVERFLOW)
                        size.toLong()
                    }
                    is VfsResult.Err -> return errno(result.error.errno)
                }
                else -> return errno(Errno.EINVAL)
            }
            val offset = input.readU64(8).toLong()
            if (offset > 0 && base > Long.MAX_VALUE - offset) return errno(Errno.EOVERFLOW)
            val anchor = base + offset
            if (anchor < 0) return errno(Errno.EINVAL)
            val length = input.readU64(16).toLong()
            if (length > 0 && anchor > Long.MAX_VALUE - (length - 1)) return errno(Errno.EOVERFLOW)
            val start = if (length < 0) anchor + length else anchor
            val end = when {
                length < 0 -> anchor - 1
                length == 0L -> Long.MAX_VALUE
                else -> anchor + (length - 1)
            }
            if (start < 0 || end < start) return errno(Errno.EINVAL)
            val range = FileLockRange(start, end)
            if (command == 36) {
                if (mode == null) return errno(Errno.EINVAL)
                val requested = FileLock(mode, range)
                val conflict = file.inode.superBlock.fileLocks.query(file, requested)
                input.writeU16(0, when (conflict?.mode) {
                    FileLockMode.SHARED -> 0u
                    FileLockMode.EXCLUSIVE -> 1u
                    null -> 2u
                })
                if (conflict != null) encodeConflict(input, conflict)
                return if (memory.copyToUser(bytes)) 0L else errno(Errno.EFAULT)
            }
            if (mode == FileLockMode.SHARED && !file.access.canRead ||
                mode == FileLockMode.EXCLUSIVE && !file.access.canWrite
            ) return errno(Errno.EBADF)
            val result = file.inode.superBlock.fileLocks.acquire(
                file, mode, command == 37, FileLockDomain.OFD, range,
            )
            when (result) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        } finally {
            file.release()
        }
    }

    private fun encodeConflict(output: LittleEndianBuffer, conflict: FileLock) {
        val range = conflict.range
        val length = if (range.end == Long.MAX_VALUE) 0L else range.end - range.start + 1
        output.writeU16(2, 0u)
        output.writeU64(8, range.start.toULong())
        output.writeU64(16, length.toULong())
        output.writeU32(24, UInt.MAX_VALUE)
    }
}
