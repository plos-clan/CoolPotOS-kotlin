@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.MEMORY_REGION_ACCESS_MASK
import org.plos_clan.cpos.mem.MemoryRegionBacking
import org.plos_clan.cpos.mem.MemoryMapRequest
import org.plos_clan.cpos.mem.MemoryMapResult
import org.plos_clan.cpos.mem.MemoryRegionType
import org.plos_clan.cpos.mem.VDSFileBacking
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.isPageAligned
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

class MappedFile(private val file: org.plos_clan.cpos.fs.OpenFileDescription) :
    MemoryRegionBacking(), VDSFileBacking {
    init {
        check(file.retain())
    }

    override val immutablePageSource: Any?
        get() = file.immutablePageSource

    override val sharedMemoryIdentity: Any
        get() = file.inode

    override fun read(offset: ULong, destination: ByteArray): Int {
        val result = file.readAt(offset, ByteArrayBuffer(destination), 0, destination.size)
        return if (result.isSuccess) result.bytesTransferred else result.raw.toInt()
    }

    override fun close() = file.release()

    override val getFile get() = file
}

private fun mmapResult(result: MemoryMapResult<ULong>): Long = when (result) {
    is MemoryMapResult.Ok -> result.value.toLong()
    is MemoryMapResult.Err -> errno(result.errno)
}

private fun memoryMapStatus(result: MemoryMapResult<Unit>): Long = when (result) {
    is MemoryMapResult.Ok -> 0L
    is MemoryMapResult.Err -> errno(result.errno)
}

fun sysMprotect(regs: PtraceRegisters, process: Process): Long {
    val protection = regs[PtraceRegisters.IDX_RDX]
    if ((protection and SUPPORTED_PROT.inv()) != 0uL) {
        return errno(Errno.EINVAL)
    }
    return memoryMapStatus(
        process.addressSpace.protect(
            address = regs[PtraceRegisters.IDX_RDI],
            length = regs[PtraceRegisters.IDX_RSI],
            access = protection and MEMORY_REGION_ACCESS_MASK,
        ),
    )
}

fun sysMunmap(regs: PtraceRegisters, process: Process): Long =
    memoryMapStatus(
        process.addressSpace.unmap(
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
    if (!offset.isPageAligned() ||
        anonymous && offset != 0uL
    ) {
        return errno(Errno.EINVAL)
    }

    val shared = mapType != MAP_PRIVATE
    val fixed = (flags and (MAP_FIXED or MAP_FIXED_NOREPLACE)) != 0uL
    val noReplace = (flags and MAP_FIXED_NOREPLACE) != 0uL
    val access = protection and SUPPORTED_PROT
    if (fixed && !hint.isPageAligned()) {
        return errno(Errno.EINVAL)
    }

    if (anonymous) {
        return mmapResult(
            process.addressSpace.map(
                MemoryMapRequest(
                    hint = hint,
                    length = length,
                    access = access,
                    fixed = fixed,
                    noReplace = noReplace,
                    shared = shared,
                    type = MemoryRegionType.ANONYMOUS,
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

        val backing = MappedFile(file)
        return try {
            mmapResult(
                process.addressSpace.map(
                    MemoryMapRequest(
                        hint = hint,
                        length = length,
                        access = access,
                        fixed = fixed,
                        noReplace = noReplace,
                        shared = shared,
                        type = MemoryRegionType.FILE,
                        offset = offset,
                        backing = backing,
                        populate = (flags and (MAP_POPULATE or MAP_LOCKED)) != 0uL,
                    ),
                ),
            )
        } finally {
            backing.release()
        }
    } finally {
        file.release()
    }
}
