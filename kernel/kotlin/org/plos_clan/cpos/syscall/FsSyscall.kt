@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.CreateDisposition
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.copyPath
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.syscall.Syscall.userMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.PtraceRegisters

private const val IO_CHUNK_SIZE = 64 * 1024
private const val MAX_RW_COUNT = 0x7ffff000uL
private const val IO_VECTOR_SIZE = ULong.SIZE_BYTES * 2
private const val MAX_IO_VECTORS = 1024
private const val AT_FDCWD = -100L
private const val POLL_FD_SIZE = 8
private const val MAX_POLL_FDS = 1024
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000uL

fun sysOpen(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    return open(
        process = process,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RSI],
        rawMode = regs[PtraceRegisters.IDX_RDX],
    )
}

fun sysOpenAt(regs: PtraceRegisters, process: Process): Long {
    val pathname = copyPath(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val dirFd = regs[PtraceRegisters.IDX_RDI].toLong()
    if (pathname.firstOrNull() != '/'.code.toByte() && dirFd != AT_FDCWD) {
        return errno(Errno.ENOTSUP)
    }
    return open(
        process = process,
        pathname = pathname,
        rawFlags = regs[PtraceRegisters.IDX_RDX],
        rawMode = regs[PtraceRegisters.IDX_R10],
    )
}

private fun open(
    process: Process,
    pathname: ByteArray,
    rawFlags: ULong,
    rawMode: ULong,
): Long {
    if (rawFlags > UInt.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val flags = rawFlags.toInt()
    if (flags and SUPPORTED_OPEN_FLAGS.inv() != 0) {
        return errno(Errno.EINVAL)
    }
    if (flags and OpenFlags.O_PATH != 0 ||
        flags and OpenFlags.O_TMPFILE == OpenFlags.O_TMPFILE
    ) {
        return errno(Errno.ENOTSUP)
    }

    val access = when (flags and OpenFlags.O_ACCMODE) {
        OpenFlags.O_RDONLY -> AccessMode.READ
        OpenFlags.O_WRONLY -> AccessMode.WRITE
        OpenFlags.O_RDWR -> AccessMode.READ_WRITE
        else -> return errno(Errno.EINVAL)
    }
    val create = when {
        flags and OpenFlags.O_CREAT == 0 -> CreateDisposition.OPEN_EXISTING
        flags and OpenFlags.O_EXCL != 0 -> CreateDisposition.CREATE_NEW
        else -> CreateDisposition.OPEN_OR_CREATE
    }
    val context = FileSystemManager.kernelContext
        ?: return errno(VfsError.NOT_FOUND.errno)
    val opened = FileSystemManager.vfs.open(
        context = context,
        pathname = VfsPathname.fromBytes(pathname),
        options = OpenOptions(
            access = access,
            create = create,
            createMode = FileMode(rawMode.toUInt() and 0x1ffu),
            truncate = flags and OpenFlags.O_TRUNC != 0,
            append = flags and OpenFlags.O_APPEND != 0,
            directoryOnly = flags and OpenFlags.O_DIRECTORY != 0,
            followFinalSymlink = flags and OpenFlags.O_NOFOLLOW == 0,
        ),
    )
    val file = when (opened) {
        is VfsResult.Ok -> opened.value
        is VfsResult.Err -> return errno(opened.error.errno)
    }
    val descriptorFlags = if (flags and OpenFlags.O_CLOEXEC != 0) {
        FileDescriptorFlags.FD_CLOEXEC
    } else {
        0uL
    }
    return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
        file.release()
        errno(Errno.EMFILE)
    }
}

fun sysClose(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    return if (process.fdTable.close(fd)) 0L else errno(Errno.EBADF)
}

fun sysPoll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) {
        return errno(Errno.EINVAL)
    }

    val count = countValue.toInt()
    val byteCount = count * POLL_FD_SIZE
    val userFds = UserMemory(
        process.vma,
        regs[PtraceRegisters.IDX_RDI],
    )
    val descriptors = userFds.copyFromUser(byteCount)
        ?: return errno(Errno.EFAULT)
    val timeoutMilliseconds = regs[PtraceRegisters.IDX_RDX].toInt()
    if (timeoutMilliseconds > 0 && !Hpet.isReady) {
        return errno(Errno.EIO)
    }

    val timeoutNanoseconds = if (timeoutMilliseconds > 0) {
        timeoutMilliseconds.toULong() * NANOSECONDS_PER_MILLISECOND
    } else {
        0uL
    }
    val startTime = if (timeoutMilliseconds > 0) Hpet.nanoTime() else 0uL

    while (true) {
        val ready = scanPollDescriptors(process, descriptors, count)
        val timedOut = timeoutMilliseconds == 0 ||
            (timeoutMilliseconds > 0 && Hpet.nanoTime() - startTime >= timeoutNanoseconds)
        if (ready != 0 || timedOut) {
            return if (userFds.copyToUser(descriptors)) ready.toLong()
            else errno(Errno.EFAULT)
        }

        bridge.wait_for_interrupt()
    }
}

private fun scanPollDescriptors(
    process: Process,
    descriptors: ByteArray,
    count: Int,
): Int {
    var ready = 0
    repeat(count) { index ->
        val offset = index * POLL_FD_SIZE
        val fd = descriptors.readI32LE(offset)
        val requested = descriptors.readU16LE(offset + Int.SIZE_BYTES)
        val returned = when {
            fd < 0 -> 0
            else -> {
                val file = process.fdTable.acquire(fd)
                if (file == null) {
                    PollEvents.POLLNVAL
                } else {
                    try {
                        val result = file.poll(requested)
                        if (result < 0) {
                            PollEvents.POLLERR
                        } else {
                            result.toInt() and
                                (requested or PollEvents.UNCONDITIONALLY_REPORTED)
                        }
                    } finally {
                        file.release()
                    }
                }
            }
        }

        descriptors.writeU16LE(offset + Int.SIZE_BYTES + Short.SIZE_BYTES, returned)
        if (returned != 0) {
            ready++
        }
    }
    return ready
}

private fun ByteArray.readI32LE(offset: Int): Int =
    (this[offset].toUByte().toUInt() or
        (this[offset + 1].toUByte().toUInt() shl 8) or
        (this[offset + 2].toUByte().toUInt() shl 16) or
        (this[offset + 3].toUByte().toUInt() shl 24)).toInt()

private fun ByteArray.readU16LE(offset: Int): Int =
    this[offset].toUByte().toInt() or
        (this[offset + 1].toUByte().toInt() shl 8)

private fun ByteArray.writeU16LE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

fun sysRead(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
        if (requested == 0uL) {
            return 0L
        }

        val userAddress = regs[PtraceRegisters.IDX_RSI]
        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        var transferred = 0uL
        while (transferred < requested) {
            val count = minOf(
                requested - transferred,
                buffer.size.toULong(),
            ).toInt()
            val user = userMemory(process, userAddress, transferred)
                ?: return partialOrError(transferred, Errno.EFAULT)
            if (!user.isWritable(count)) {
                return partialOrError(transferred, Errno.EFAULT)
            }

            val result = file.read(buffer, count = count)
            if (!result.isSuccess) {
                return if (transferred != 0uL) transferred.toLong() else result.raw
            }
            val current = result.bytesTransferred
            if (current == 0) {
                break
            }
            if (!user.copyToUser(buffer, size = current)) {
                return partialOrError(transferred, Errno.EFAULT)
            }
            transferred += current.toULong()
            if (current < count) {
                break
            }
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

fun sysWrite(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val requested = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT)
        if (requested == 0uL) {
            return 0L
        }

        val userAddress = regs[PtraceRegisters.IDX_RSI]
        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        var transferred = 0uL
        while (transferred < requested) {
            val count = minOf(
                requested - transferred,
                buffer.size.toULong(),
            ).toInt()
            val user = userMemory(process, userAddress, transferred)
                ?: return partialOrError(transferred, Errno.EFAULT)
            if (!user.copyFromUser(buffer, size = count)) {
                return partialOrError(transferred, Errno.EFAULT)
            }

            val result = file.write(buffer, count = count)
            if (!result.isSuccess) {
                return if (transferred != 0uL) transferred.toLong() else result.raw
            }
            val current = result.bytesTransferred
            if (current == 0) {
                break
            }
            transferred += current.toULong()
            if (current < count) {
                break
            }
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

fun sysReadv(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val vectorCountValue = regs[PtraceRegisters.IDX_RDX]
        if (vectorCountValue > MAX_IO_VECTORS.toULong()) {
            return errno(Errno.EINVAL)
        }

        val vectorCount = vectorCountValue.toInt()
        val vectors = UserMemory(
            process.vma,
            regs[PtraceRegisters.IDX_RSI],
        ).copyFromUser(vectorCount * IO_VECTOR_SIZE)
            ?: return errno(Errno.EFAULT)
        val requested = vectors.totalIoVectorLength(vectorCount)
        if (requested == 0uL) {
            return 0L
        }

        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        var transferred = 0uL
        repeat(vectorCount) { index ->
            val vectorOffset = index * IO_VECTOR_SIZE
            val userAddress = vectors.readU64LE(vectorOffset)
            val vectorLength = minOf(
                vectors.readU64LE(vectorOffset + ULong.SIZE_BYTES),
                requested - transferred,
            )
            var currentOffset = 0uL

            while (currentOffset < vectorLength) {
                val count = minOf(
                    vectorLength - currentOffset,
                    buffer.size.toULong(),
                ).toInt()
                val user = userMemory(process, userAddress, currentOffset)
                    ?: return partialOrError(transferred, Errno.EFAULT)
                if (!user.isWritable(count)) {
                    return partialOrError(transferred, Errno.EFAULT)
                }

                val result = file.read(buffer, count = count)
                if (!result.isSuccess) {
                    return if (transferred != 0uL) transferred.toLong() else result.raw
                }
                val current = result.bytesTransferred
                if (current == 0) {
                    return transferred.toLong()
                }
                if (!user.copyToUser(buffer, size = current)) {
                    return partialOrError(transferred, Errno.EFAULT)
                }

                transferred += current.toULong()
                currentOffset += current.toULong()
                if (current < count) {
                    return transferred.toLong()
                }
            }
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

fun sysWritev(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    try {
        val vectorCountValue = regs[PtraceRegisters.IDX_RDX]
        if (vectorCountValue > MAX_IO_VECTORS.toULong()) {
            return errno(Errno.EINVAL)
        }

        val vectorCount = vectorCountValue.toInt()
        val vectors = UserMemory(
            process.vma,
            regs[PtraceRegisters.IDX_RSI],
        ).copyFromUser(vectorCount * IO_VECTOR_SIZE)
            ?: return errno(Errno.EFAULT)
        val requested = vectors.totalIoVectorLength(vectorCount)
        if (requested == 0uL) {
            return 0L
        }

        val buffer = ByteArray(minOf(requested, IO_CHUNK_SIZE.toULong()).toInt())
        var transferred = 0uL
        repeat(vectorCount) { index ->
            val vectorOffset = index * IO_VECTOR_SIZE
            val userAddress = vectors.readU64LE(vectorOffset)
            val vectorLength = minOf(
                vectors.readU64LE(vectorOffset + ULong.SIZE_BYTES),
                requested - transferred,
            )
            var currentOffset = 0uL

            while (currentOffset < vectorLength) {
                val count = minOf(
                    vectorLength - currentOffset,
                    buffer.size.toULong(),
                ).toInt()
                val user = userMemory(process, userAddress, currentOffset)
                    ?: return partialOrError(transferred, Errno.EFAULT)
                if (!user.copyFromUser(buffer, size = count)) {
                    return partialOrError(transferred, Errno.EFAULT)
                }

                val result = file.write(buffer, count = count)
                if (!result.isSuccess) {
                    return if (transferred != 0uL) transferred.toLong() else result.raw
                }
                val current = result.bytesTransferred
                if (current == 0) {
                    return transferred.toLong()
                }

                transferred += current.toULong()
                currentOffset += current.toULong()
                if (current < count) {
                    return transferred.toLong()
                }
            }
        }
        return transferred.toLong()
    } finally {
        file.release()
    }
}

private fun ByteArray.totalIoVectorLength(vectorCount: Int): ULong {
    var total = 0uL
    repeat(vectorCount) { index ->
        val length = readU64LE(index * IO_VECTOR_SIZE + ULong.SIZE_BYTES)
        total += minOf(length, MAX_RW_COUNT - total)
    }
    return total
}

private fun ByteArray.readU64LE(offset: Int): ULong {
    var value = 0uL
    repeat(ULong.SIZE_BYTES) { byteIndex ->
        value = value or
            (this[offset + byteIndex].toUByte().toULong() shl (byteIndex * Byte.SIZE_BITS))
    }
    return value
}

fun sysIoctl(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        file.ioctl(
            command = regs[PtraceRegisters.IDX_RSI].toInt(),
            args = UserMemory(
                process.vma,
                regs[PtraceRegisters.IDX_RDX],
            ),
        )
    } finally {
        file.release()
    }
}

fun sysGetCWD(regs: PtraceRegisters, process: Process): Long {
    val userAddress = regs[PtraceRegisters.IDX_RDI]
    val length = regs[PtraceRegisters.IDX_RSI]
    if (userAddress == 0UL) return errno(Errno.EFAULT)

    val path = when (
        val result = FileSystemManager.vfs.absolutePath(
            process.getFSContext(),
            process.getFSContext().workingDirectory,
        )
    ) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }

    val result = ByteArray(path.size + 1)
    path.copyInto(result)

    if (length < result.size.toULong()) {
        return errno(Errno.ERANGE)
    }

    if (!UserMemory(
            process.vma,
            userAddress,
        ).copyToUser(result)
    ) {
        return errno(Errno.EFAULT)
    }

    return result.size.toLong()
}
