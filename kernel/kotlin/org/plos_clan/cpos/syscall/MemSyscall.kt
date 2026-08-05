@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.mem.VMA_EXEC
import org.plos_clan.cpos.mem.VMA_READ
import org.plos_clan.cpos.mem.VMA_WRITE
import org.plos_clan.cpos.mem.VmaMapRequest
import org.plos_clan.cpos.mem.VmaResult
import org.plos_clan.cpos.mem.VmaType
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters

private const val MAP_SHARED = 0x01uL
private const val MAP_PRIVATE = 0x02uL
private const val MAP_SHARED_VALIDATE = 0x03uL
private const val MAP_TYPE = 0x0fuL
private const val MAP_FIXED = 0x10uL
private const val MAP_ANONYMOUS = 0x20uL
private const val MAP_GROWSDOWN = 0x0100uL
private const val MAP_DENYWRITE = 0x0800uL
private const val MAP_EXECUTABLE = 0x1000uL
private const val MAP_LOCKED = 0x2000uL
private const val MAP_NORESERVE = 0x4000uL
private const val MAP_POPULATE = 0x8000uL
private const val MAP_NONBLOCK = 0x10000uL
private const val MAP_STACK = 0x20000uL
private const val MAP_HUGETLB = 0x40000uL
private const val MAP_SYNC = 0x80000uL
private const val MAP_FIXED_NOREPLACE = 0x100000uL
private const val SUPPORTED_MMAP_FLAGS = 0x001f_f93fuL

private const val PROT_READ = 0x1uL
private const val PROT_WRITE = 0x2uL
private const val PROT_EXEC = 0x4uL

private fun mmapResult(result: VmaResult<ULong>): Long = when (result) {
    is VmaResult.Ok -> result.value.toLong()
    is VmaResult.Err -> errno(result.errno)
}

private fun vmaStatus(result: VmaResult<Unit>): Long = when (result) {
    is VmaResult.Ok -> 0L
    is VmaResult.Err -> errno(result.errno)
}

fun sysMprotect(regs: PtraceRegisters, process: Process): Long {
    val protection = regs[PtraceRegisters.IDX_RDX]
    if ((protection and SUPPORTED_PROT.inv()) != 0uL) {
        return errno(Errno.EINVAL)
    }
    return vmaStatus(
        process.vma.protect(
            address = regs[PtraceRegisters.IDX_RDI],
            length = regs[PtraceRegisters.IDX_RSI],
            access = protection and (VMA_READ or VMA_WRITE or VMA_EXEC),
        ),
    )
}

fun sysMunmap(regs: PtraceRegisters, process: Process): Long =
    vmaStatus(
        process.vma.unmap(
            address = regs[PtraceRegisters.IDX_RDI],
            length = regs[PtraceRegisters.IDX_RSI],
        ),
    )

fun sysMmap(regs: PtraceRegisters, process: Process): Long {
    val hint = regs[PtraceRegisters.IDX_RDI]
    val length = regs[PtraceRegisters.IDX_RSI]
    val protection = regs[PtraceRegisters.IDX_RDX]
    val flags = regs[PtraceRegisters.IDX_R10]
    val fdValue = regs[PtraceRegisters.IDX_R8]
    val offset = regs[PtraceRegisters.IDX_R9]
    val mapType = flags and MAP_TYPE
    val anonymous = (flags and MAP_ANONYMOUS) != 0uL

    if (length == 0uL || (protection and SUPPORTED_PROT.inv()) != 0uL) {
        return errno(Errno.EINVAL)
    }
    if (mapType != MAP_PRIVATE &&
        mapType != MAP_SHARED &&
        mapType != MAP_SHARED_VALIDATE
    ) {
        return errno(Errno.EINVAL)
    }
    val unknownFlags = flags and SUPPORTED_MMAP_FLAGS.inv()
    if (unknownFlags != 0uL && mapType == MAP_SHARED_VALIDATE) {
        return errno(Errno.ENOTSUP)
    }
    if ((flags and MAP_SYNC) != 0uL && mapType != MAP_SHARED_VALIDATE) {
        return errno(Errno.EINVAL)
    }
    if ((flags and MAP_HUGETLB) != 0uL || (flags and MAP_SYNC) != 0uL) {
        return errno(Errno.ENOTSUP)
    }
    if ((offset and (PAGE_SIZE_BYTES - 1uL)) != 0uL ||
        anonymous && offset != 0uL
    ) {
        return errno(Errno.EINVAL)
    }

    val shared = mapType != MAP_PRIVATE
    val fixed = (flags and (MAP_FIXED or MAP_FIXED_NOREPLACE)) != 0uL
    val noReplace = (flags and MAP_FIXED_NOREPLACE) != 0uL
    val access = protection and SUPPORTED_PROT
    if (fixed && (hint and (PAGE_SIZE_BYTES - 1uL)) != 0uL) {
        return errno(Errno.EINVAL)
    }

    if (anonymous) {
        return mmapResult(
            process.vma.map(
                VmaMapRequest(
                    hint = hint,
                    length = length,
                    access = access,
                    fixed = fixed,
                    noReplace = noReplace,
                    shared = shared,
                    type = VmaType.ANONYMOUS,
                    populate = (flags and (MAP_POPULATE or MAP_LOCKED)) != 0uL,
                ),
            ),
        )
    }

    val fd = fileDescriptor(fdValue) ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        if (file.inode.type == InodeType.DIRECTORY) {
            return errno(Errno.EISDIR)
        }
        if (!file.access.canRead) {
            return errno(Errno.EACCES)
        }
        if (shared && (protection and PROT_WRITE) != 0uL && !file.access.canWrite) {
            return errno(Errno.EACCES)
        }
        if (shared && (protection and PROT_WRITE) != 0uL) {
            return errno(Errno.ENOTSUP)
        }

        return mmapResult(
            process.vma.map(
                VmaMapRequest(
                    hint = hint,
                    length = length,
                    access = access,
                    fixed = fixed,
                    noReplace = noReplace,
                    shared = shared,
                    type = VmaType.FILE,
                    offset = offset,
                    pageReader = { pageOffset, destination ->
                        val result = file.readAt(pageOffset, destination)
                        if (result.isSuccess) result.bytesTransferred else result.raw.toInt()
                    },
                ),
            ),
        )
    } finally {
        file.release()
    }
}
