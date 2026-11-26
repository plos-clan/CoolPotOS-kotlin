@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlin.native.getLongAt
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.FileLockMode
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileBackend
import org.plos_clan.cpos.fs.vfs.SeekOrigin
import org.plos_clan.cpos.fs.vfs.SealableFile
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.IoScratch
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.fs.FsConstants.CLOSE_RANGE_CLOEXEC
import org.plos_clan.cpos.syscall.fs.FsConstants.F_ADD_SEALS
import org.plos_clan.cpos.syscall.fs.FsConstants.F_GET_SEALS
import org.plos_clan.cpos.syscall.fs.FsConstants.F_DUPFD
import org.plos_clan.cpos.syscall.fs.FsConstants.F_DUPFD_CLOEXEC
import org.plos_clan.cpos.syscall.fs.FsConstants.F_GETFD
import org.plos_clan.cpos.syscall.fs.FsConstants.F_GETFD_FLAGS
import org.plos_clan.cpos.syscall.fs.FsConstants.F_GETFL
import org.plos_clan.cpos.syscall.fs.FsConstants.F_GETOWN
import org.plos_clan.cpos.syscall.fs.FsConstants.F_SETFD
import org.plos_clan.cpos.syscall.fs.FsConstants.F_SETFL
import org.plos_clan.cpos.syscall.fs.FsConstants.F_SETOWN
import org.plos_clan.cpos.syscall.fs.FsConstants.MAX_POLL_FDS
import org.plos_clan.cpos.syscall.fs.FsConstants.POLL_FD_SIZE
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun lseek(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val whenceValue = regs[PtraceRegisters.IDX_RDX]
    if (whenceValue > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val origin = when (whenceValue.toInt()) {
        0 -> SeekOrigin.START
        1 -> SeekOrigin.CURRENT
        2 -> SeekOrigin.END
        else -> return errno(Errno.EINVAL)
    }
    val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
    return try {
        when (val result = file.seek(
            process.vfsOperationContext,
            regs[PtraceRegisters.IDX_RSI].toLong(),
            origin,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun dup(regs: PtraceRegisters, process: Process): Long {
    val oldFd = regs[PtraceRegisters.IDX_RDI].toInt()
    return when (val result = process.fdTable.duplicate(oldFd, 0, 0uL)) {
        is VfsResult.Ok -> result.value.toLong()
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun dup2(regs: PtraceRegisters, process: Process): Long {
    val oldFd = regs[PtraceRegisters.IDX_RDI].toInt()
    val newFd = regs[PtraceRegisters.IDX_RSI].toInt()
    return when (val result = process.fdTable.duplicateTo(
        process.vfsOperationContext, oldFd, newFd,
    )) {
        is VfsResult.Ok -> result.value.toLong()
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun dup3(regs: PtraceRegisters, process: Process): Long {
    val oldFd = regs[PtraceRegisters.IDX_RDI].toInt()
    val newFd = regs[PtraceRegisters.IDX_RSI].toInt()
    val flags = regs[PtraceRegisters.IDX_RDX].toInt()
    if (oldFd == newFd || flags and OpenFlags.O_CLOEXEC.inv() != 0) {
        return errno(Errno.EINVAL)
    }
    val descriptorFlags = if (flags and OpenFlags.O_CLOEXEC != 0) FileDescriptorFlags.FD_CLOEXEC else 0uL
    return when (val result = process.fdTable.duplicateTo(
        process.vfsOperationContext, oldFd, newFd, descriptorFlags,
    )) {
        is VfsResult.Ok -> result.value.toLong()
        is VfsResult.Err -> errno(result.error.errno)
    }
}

internal fun closeRange(regs: PtraceRegisters, process: Process): Long {
    val first = regs[PtraceRegisters.IDX_RDI].toUInt()
    val last = regs[PtraceRegisters.IDX_RSI].toUInt()
    val flags = regs[PtraceRegisters.IDX_RDX].toUInt()
    if (first > last || flags and CLOSE_RANGE_CLOEXEC.inv() != 0u) {
        return errno(Errno.EINVAL)
    }

    val action = if (flags and CLOSE_RANGE_CLOEXEC == 0u) {
        FileDescriptorTable.CloseRangeAction.CLOSE
    } else {
        FileDescriptorTable.CloseRangeAction.MARK_CLOSE_ON_EXEC
    }
    process.fdTable.closeRange(process.vfsOperationContext, first, last, action)
    return 0L
}

internal fun flock(regs: PtraceRegisters, process: Process): Long {
    val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EBADF)
    val operation = regs[PtraceRegisters.IDX_RSI].toUInt()
    val command = operation and 4u.inv()
    if (command != 1u && command != 2u && command != 8u) return errno(Errno.EINVAL)
    val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
    return try {
        if (file.access == AccessMode.PATH) return errno(Errno.EBADF)
        val locks = file.inode.superBlock.fileLocks
        val mode = when (command) {
            1u -> FileLockMode.SHARED
            2u -> FileLockMode.EXCLUSIVE
            else -> null
        }
        when (val result = locks.acquire(file, mode, operation and 4u != 0u)) {
            is VfsResult.Ok -> 0L
            is VfsResult.Err -> errno(result.error.errno)
        }
    } finally {
        file.release()
    }
}

internal fun fcntl(regs: PtraceRegisters, process: Process): Long {
    val fd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val command = regs[PtraceRegisters.IDX_RSI]
    if (command > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val argument = regs[PtraceRegisters.IDX_RDX]
    return when (command.toInt()) {
        5, 6, 7, 36, 37, 38 -> RecordLock.execute(process, fd, command.toInt(), argument)
        F_DUPFD,
        F_DUPFD_CLOEXEC,
        -> {
            if (!process.fdTable.contains(fd)) return errno(Errno.EBADF)
            val limit = process.fdTable.limit.toULong()
            if (argument > Int.MAX_VALUE.toULong() || argument >= limit) {
                return errno(Errno.EINVAL)
            }
            val flags = if (command.toInt() == F_DUPFD_CLOEXEC) F_GETFD_FLAGS else 0uL
            when (val result = process.fdTable.duplicate(fd, argument.toInt(), flags)) {
                is VfsResult.Ok -> result.value.toLong()
                is VfsResult.Err -> errno(result.error.errno)
            }
        }

        F_GETFD -> process.fdTable.descriptorFlags(fd)?.toLong() ?: errno(Errno.EBADF)

        F_SETFD -> if (process.fdTable.setDescriptorFlags(fd, argument and F_GETFD_FLAGS)) {
            0L
        } else {
            errno(Errno.EBADF)
        }

        F_GETFL -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            try {
                val access = when (file.access) {
                    AccessMode.READ -> OpenFlags.O_RDONLY
                    AccessMode.WRITE -> OpenFlags.O_WRONLY
                    AccessMode.READ_WRITE -> OpenFlags.O_RDWR
                    AccessMode.EXECUTE -> OpenFlags.O_RDONLY
                    AccessMode.PATH -> OpenFlags.O_PATH
                }
                val largeFile = if (file.backend is AnonymousFileBackend) {
                    0
                } else {
                    OpenFlags.O_LARGEFILE
                }
                (access or file.getStatusFlags() or largeFile).toLong()
            } finally {
                file.release()
            }
        }

        F_SETFL -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            val caller = process.vfsOperationContext
            try {
                val enablesNoAtime = argument.toInt() and OpenFlags.O_NOATIME != 0 &&
                    file.getStatusFlags() and OpenFlags.O_NOATIME == 0
                if (enablesNoAtime && !caller.privileged) {
                    val owner = when (
                        val result = file.inode.attributes(caller)
                    ) {
                        is VfsResult.Ok -> result.value.metadata.uid
                        is VfsResult.Err -> return errno(result.error.errno)
                    }
                    if (caller.uid != owner) return errno(Errno.EPERM)
                }
                file.setStatusFlags(argument.toInt())
                0L
            } finally {
                file.release()
            }
        }

        F_GETOWN,
        F_SETOWN,
        -> if (process.fdTable.contains(fd)) 0L else errno(Errno.EBADF)

        F_ADD_SEALS,
        F_GET_SEALS,
        -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            try {
                if (file.access == AccessMode.PATH) return errno(Errno.EBADF)
                val sealable = file.inode.backend as? SealableFile ?: return errno(Errno.EINVAL)
                if (command.toInt() == F_GET_SEALS) return sealable.getSeals().toLong()
                if (!file.access.canWrite) return errno(Errno.EPERM)
                when (val result = sealable.addSeals(file.inode, argument.toInt())) {
                    is VfsResult.Ok -> 0L
                    is VfsResult.Err -> errno(result.error.errno)
                }
            } finally {
                file.release()
            }
        }

        else -> errno(Errno.EINVAL)
    }
}

internal fun poll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val timeoutMilliseconds = regs[PtraceRegisters.IDX_RDX].toInt()
    val timeout = PollTimeout.fromMilliseconds(timeoutMilliseconds) ?: return errno(Errno.EIO)
    return waitForPoll(regs, process, countValue.toInt(), timeout, null)
}

internal fun ppoll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val timeoutAddress = regs[PtraceRegisters.IDX_RDX]
    val timeout = PollTimeout.read(process, timeoutAddress) { return errno(it) }
    val address = regs[PtraceRegisters.IDX_R10]
    val size = regs[PtraceRegisters.IDX_R8]
    val mask = PollTimeout.readMask(process, address, size) { return errno(it) }
    return waitForPoll(regs, process, countValue.toInt(), timeout, mask)
}

private fun waitForPoll(
    regs: PtraceRegisters,
    process: Process,
    count: Int,
    timeout: PollTimeout,
    temporaryMask: ULong?,
): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val userFds = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
    val size = count * POLL_FD_SIZE
    val caller = process.vfsOperationContext
    return IoScratch.use(size) { bytes ->
        if (!userFds.readInto(bytes, size)) return errno(Errno.EFAULT)
        val fds = PollFds(bytes, count)
        val ready = timeout.wait(regs, thread, temporaryMask) { fds.scan(process, caller, it) }
        if (ready < 0 || fds.store(userFds)) ready.toLong() else errno(Errno.EFAULT)
    }
}

internal fun pselect6(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val nfdsValue = regs[PtraceRegisters.IDX_RDI]
    if (nfdsValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val nfds = nfdsValue.toInt()
    val timeoutAddress = regs[PtraceRegisters.IDX_R8]
    val timeout = PollTimeout.read(process, timeoutAddress) { return errno(it) }
    val signalMaskAddress = regs[PtraceRegisters.IDX_R9]
    val signalMask = if (signalMaskAddress == 0uL) null else {
        val descriptor = UserMemory(process.addressSpace, signalMaskAddress)
        descriptor.readRecord(2 * ULong.SIZE_BYTES) { input ->
            val address = input.readU64(0)
            val size = input.readU64(ULong.SIZE_BYTES)
            PollTimeout.readMask(process, address, size) { return errno(it) }
                ?: thread.signals.mask
        } ?: return errno(Errno.EFAULT)
    }
    val caller = process.vfsOperationContext
    return SelectSets.use(process, regs, nfds) { sets ->
        timeout.wait(regs, thread, signalMask) { subscription ->
            sets.scan(nfds) { fd, events -> process.pollFd(fd, events, caller, subscription) }
        }
    }
}

private class PollFds(bytes: ByteArray, private val count: Int) {
    private val fields = LittleEndianBuffer(bytes)

    fun scan(process: Process, caller: VfsOperationContext, subscription: PollSubscription?): Int {
        var ready = 0
        for (index in 0 until count) {
            val offset = index * POLL_FD_SIZE
            val fd = fields.readU32(offset).toInt()
            val events = fields.readU16(offset + Int.SIZE_BYTES).toInt()
            val result = if (fd < 0) 0 else {
                process.pollFd(fd, events, caller, subscription, PollEvents.POLLNVAL)
            }
            val reported = if (result < 0) PollEvents.POLLERR
            else result and (events or PollEvents.UNCONDITIONALLY_REPORTED)
            fields.writeU16(offset + REVENTS_OFFSET, reported.toUShort())
            if (reported != 0) ready++
        }
        return ready
    }

    fun store(memory: UserMemory): Boolean =
        memory.writeFields(fields, count, POLL_FD_SIZE, REVENTS_OFFSET, Short.SIZE_BYTES)

    private companion object {
        const val REVENTS_OFFSET = Int.SIZE_BYTES + Short.SIZE_BYTES
    }
}

private class SelectSets private constructor(
    process: Process,
    regs: PtraceRegisters,
    private val bytes: ByteArray,
    private val size: Int,
) {
    private val read = process.setAt(regs[PtraceRegisters.IDX_RSI])
    private val write = process.setAt(regs[PtraceRegisters.IDX_RDX])
    private val except = process.setAt(regs[PtraceRegisters.IDX_R10])

    private inline fun eachSet(copy: (UserMemory?, Int) -> Boolean): Boolean =
        copy(read, 0) && copy(write, size) && copy(except, 2 * size)

    private fun load(): Boolean {
        bytes.fill(0, 0, SET_COUNT * 2 * size)
        return eachSet { memory, start ->
            memory == null || memory.readInto(bytes, size, start = start)
        }
    }

    private fun store(): Boolean = eachSet { memory, start ->
        memory == null || memory.writeFrom(bytes, size, start = SET_COUNT * size + start)
    }

    inline fun scan(nfds: Int, poll: (Int, Int) -> Int): Int {
        var count = 0
        for (base in 0 until nfds step ULong.SIZE_BITS) {
            val ready = scanWord(base, nfds, poll)
            if (ready < 0) return ready
            count += ready
        }
        return count
    }

    private inline fun scanWord(base: Int, nfds: Int, poll: (Int, Int) -> Int): Int {
        val offset = base / Byte.SIZE_BITS
        val readers = bytes.getLongAt(offset).toULong()
        val writers = bytes.getLongAt(size + offset).toULong()
        val exceptions = bytes.getLongAt(2 * size + offset).toULong()
        var bits = readers or writers or exceptions
        var count = 0
        while (bits != 0uL) {
            val fd = base + bits.countTrailingZeroBits()
            if (fd >= nfds) break
            val bit = bits.takeLowestOneBit()
            val input = if (readers and bit != 0uL) READ_EVENTS else 0
            val output = if (writers and bit != 0uL) WRITE_EVENTS else 0
            val priority = if (exceptions and bit != 0uL) PollEvents.POLLPRI else 0
            val ready = poll(fd, input or output or priority)
            if (ready < 0) return ready
            count += report(0, fd, ready and input)
            count += report(size, fd, ready and output)
            count += report(2 * size, fd, ready and priority)
            bits = bits xor bit
        }
        return count
    }

    private fun report(start: Int, fd: Int, events: Int): Int {
        if (events == 0) return 0
        val index = SET_COUNT * size + start + fd / Byte.SIZE_BITS
        val mask = 1 shl (fd % Byte.SIZE_BITS)
        bytes[index] = (bytes[index].toInt() or mask).toByte()
        return 1
    }

    companion object {
        private const val SET_COUNT = 3
        private const val READ_EVENTS = PollEvents.NORMAL_INPUT or PollEvents.POLLRDBAND or
            PollEvents.POLLERR or PollEvents.POLLHUP
        private const val WRITE_EVENTS = PollEvents.NORMAL_OUTPUT or PollEvents.POLLWRBAND or
            PollEvents.POLLERR

        inline fun use(
            process: Process,
            regs: PtraceRegisters,
            nfds: Int,
            scan: (SelectSets) -> Int,
        ): Long {
            val words = (nfds + ULong.SIZE_BITS - 1) / ULong.SIZE_BITS
            val size = words * ULong.SIZE_BYTES
            return IoScratch.use(SET_COUNT * 2 * size) { bytes ->
                val sets = SelectSets(process, regs, bytes, size)
                if (!sets.load()) return errno(Errno.EFAULT)
                val ready = scan(sets)
                if (ready < 0 || sets.store()) ready.toLong() else errno(Errno.EFAULT)
            }
        }

        private fun Process.setAt(address: ULong): UserMemory? =
            if (address == 0uL) null else UserMemory(addressSpace, address)
    }
}

private fun Process.pollFd(
    fd: Int,
    events: Int,
    caller: VfsOperationContext,
    subscription: PollSubscription?,
    missing: Int = -Errno.EBADF,
): Int {
    val file = fdTable.acquire(fd) ?: return missing
    return try {
        file.poll(caller, events, subscription = subscription).toInt()
    } finally {
        file.release()
    }
}

internal fun fadvise64(regs: PtraceRegisters, process: Process): Long = errno(Errno.EOK)
