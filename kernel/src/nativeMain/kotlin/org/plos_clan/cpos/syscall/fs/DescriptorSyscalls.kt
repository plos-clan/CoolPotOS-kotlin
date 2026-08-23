@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import bridge.wait_for_interrupt
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.SeekOrigin
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.SignalDelivery
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.TimeSpec
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
import org.plos_clan.cpos.syscall.fs.FsConstants.NANOSECONDS_PER_MILLISECOND
import org.plos_clan.cpos.syscall.fs.FsConstants.POLL_FD_SIZE
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
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
        when (file.inode.type) {
            InodeType.CHARACTER_DEVICE,
            InodeType.BLOCK_DEVICE,
            InodeType.PIPE,
            InodeType.SOCKET,
            -> errno(Errno.ESPIPE)
            else -> when (val result = file.seek(regs[PtraceRegisters.IDX_RSI].toLong(), origin)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> errno(result.error.errno)
            }
        }
    } finally {
        file.release()
    }
}

internal fun dup(regs: PtraceRegisters, process: Process): Long {
    val oldFd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val file = process.fdTable.acquire(oldFd) ?: return errno(Errno.EBADF)
    val newFd = process.fdTable.install(file, 0uL)
    if (newFd == null) {
        file.release()
        return errno(Errno.EMFILE)
    }
    return newFd.toLong()
}

internal fun dup2(regs: PtraceRegisters, process: Process): Long {
    val oldFd = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EBADF)
    val newFd = fileDescriptor(regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EBADF)
    return if (process.fdTable.dup2(oldFd, newFd)) {
        newFd.toLong()
    } else {
        errno(Errno.EBADF)
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
        F_DUPFD,
        F_DUPFD_CLOEXEC,
        -> {
            if (argument > Int.MAX_VALUE.toULong()) {
                return errno(Errno.EINVAL)
            }
            val flags = if (command.toInt() == F_DUPFD_CLOEXEC) F_GETFD_FLAGS else 0uL
            process.fdTable.duplicate(fd, argument.toInt(), flags)?.toLong()
                ?: errno(Errno.EBADF)
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
                    AccessMode.PATH -> OpenFlags.O_PATH
                }
                (access or file.getStatusFlags() or OpenFlags.O_LARGEFILE).toLong()
            } finally {
                file.release()
            }
        }

        F_SETFL -> {
            val file = process.fdTable.acquire(fd) ?: return errno(Errno.EBADF)
            try {
                val enablesNoAtime = argument.toInt() and OpenFlags.O_NOATIME != 0 &&
                    file.getStatusFlags() and OpenFlags.O_NOATIME == 0
                if (enablesNoAtime && process.euid != 0 &&
                    process.fsuid.toUInt() != file.inode.metadata().uid
                ) {
                    return errno(Errno.EPERM)
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

        else -> errno(Errno.EINVAL)
    }
}

internal fun poll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val timeoutMilliseconds = regs[PtraceRegisters.IDX_RDX].toInt()
    if (timeoutMilliseconds > 0 && !TscClock.isReady) {
        return errno(Errno.EIO)
    }
    val timeout = when {
        timeoutMilliseconds < 0 -> PollTimeout.INFINITE
        timeoutMilliseconds == 0 -> PollTimeout.IMMEDIATE
        else -> PollTimeout(
            TscClock.nanoTime() + timeoutMilliseconds.toULong() * NANOSECONDS_PER_MILLISECOND,
            immediate = false,
        )
    }
    return waitForPoll(regs, process, countValue.toInt(), timeout, temporaryMask = null)
}

internal fun ppoll(regs: PtraceRegisters, process: Process): Long {
    val countValue = regs[PtraceRegisters.IDX_RSI]
    if (countValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val timeoutAddress = regs[PtraceRegisters.IDX_RDX]
    val timeout = if (timeoutAddress == 0uL) {
        PollTimeout.INFINITE
    } else {
        when (val result = readPselectTimeout(process, timeoutAddress)) {
            is VfsResult.Ok -> PollTimeout(
                deadline = timeoutDeadline(result.value),
                immediate = result.value.isZero,
            )
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val maskAddress = regs[PtraceRegisters.IDX_R10]
    val mask = if (maskAddress == 0uL) {
        null
    } else {
        if (regs[PtraceRegisters.IDX_R8] != ULong.SIZE_BYTES.toULong()) {
            return errno(Errno.EINVAL)
        }
        UserMemory(process.addressSpace, maskAddress).copyFromUser(ULong.SIZE_BYTES)
            ?.let { LittleEndianBuffer(it).readU64(0) and Signal.BLOCKABLE_MASK }
            ?: return errno(Errno.EFAULT)
    }
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
    val descriptors = userFds.copyFromUser(count * POLL_FD_SIZE)
        ?: return errno(Errno.EFAULT)
    val previousMask = temporaryMask?.let { thread.signals.replaceMask(it) }
    try {
        while (true) {
            val ready = scanPollDescriptors(process, descriptors, count)
            if (ready != 0 || timeout.immediate || timeout.expired()) {
                return if (userFds.copyToUser(descriptors)) ready.toLong()
                else errno(Errno.EFAULT)
            }
            if (thread.hasPendingSignal()) {
                val returnMask = previousMask ?: return errno(Errno.EINTR)
                regs[PtraceRegisters.IDX_RAX] = errno(Errno.EINTR).toULong()
                if (SignalDelivery.deliverPending(regs, thread, returnMask)) {
                    return errno(Errno.EINTR)
                }
            }
            Scheduler.yieldCurrent()
            wait_for_interrupt()
        }
    } finally {
        if (previousMask != null && !regs.signalFrameInstalled) {
            thread.signals.mask = previousMask
        }
    }
}

internal fun pselect6(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val nfdsValue = regs[PtraceRegisters.IDX_RDI]
    if (nfdsValue > MAX_POLL_FDS.toULong()) return errno(Errno.EINVAL)
    val nfds = nfdsValue.toInt()
    val setSize = ((nfds + Long.SIZE_BITS - 1) / Long.SIZE_BITS) * ULong.SIZE_BYTES
    val requestedRead = copyFdSet(process, regs[PtraceRegisters.IDX_RSI], setSize)
        ?: return errno(Errno.EFAULT)
    val requestedWrite = copyFdSet(process, regs[PtraceRegisters.IDX_RDX], setSize)
        ?: return errno(Errno.EFAULT)
    val requestedExcept = copyFdSet(process, regs[PtraceRegisters.IDX_R10], setSize)
        ?: return errno(Errno.EFAULT)
    val timeoutAddress = regs[PtraceRegisters.IDX_R8]
    val timeout = if (timeoutAddress == 0uL) {
        null
    } else {
        when (val result = readPselectTimeout(process, timeoutAddress)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val signalMaskAddress = regs[PtraceRegisters.IDX_R9]
    val signalMask = if (signalMaskAddress == 0uL) {
        null
    } else {
        when (val result = readPselectSignalMask(process, signalMaskAddress, thread.signals.mask)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
    }
    val readyRead = ByteArray(setSize)
    val readyWrite = ByteArray(setSize)
    val readyExcept = ByteArray(setSize)
    val previousMask = signalMask?.let { thread.signals.replaceMask(it) }
    try {
        val deadline = timeout?.let(::timeoutDeadline)
        while (true) {
            requestedRead.copyInto(readyRead)
            requestedWrite.copyInto(readyWrite)
            requestedExcept.copyInto(readyExcept)
            val ready = scanSelectDescriptors(process, nfds, readyRead, readyWrite, readyExcept)
            if (ready < 0) return errno(-ready)
            val expired = deadline != null && TscClock.nanoTime() >= deadline
            if (ready != 0 || timeout?.isZero == true || expired) {
                if (!copyFdSet(process, regs[PtraceRegisters.IDX_RSI], readyRead, setSize) ||
                    !copyFdSet(process, regs[PtraceRegisters.IDX_RDX], readyWrite, setSize) ||
                    !copyFdSet(process, regs[PtraceRegisters.IDX_R10], readyExcept, setSize)
                ) return errno(Errno.EFAULT)
                return ready.toLong()
            }
            if (thread.hasPendingSignal()) {
                val returnMask = previousMask ?: return errno(Errno.EINTR)
                regs[PtraceRegisters.IDX_RAX] = errno(Errno.EINTR).toULong()
                if (SignalDelivery.deliverPending(regs, thread, returnMask)) {
                    return errno(Errno.EINTR)
                }
            }
            Scheduler.yieldCurrent()
            wait_for_interrupt()
        }
    } finally {
        if (previousMask != null && !regs.signalFrameInstalled) {
            thread.signals.mask = previousMask
        }
    }
}

//TODO implement fadvise64
internal fun fadvise64(regs: PtraceRegisters, process: Process): Long = errno(Errno.EOK)

private fun copyFdSet(process: Process, address: ULong, size: Int): ByteArray? =
    if (address == 0uL || size == 0) ByteArray(size)
    else UserMemory(process.addressSpace, address).copyFromUser(size)

private fun copyFdSet(process: Process, address: ULong, value: ByteArray, size: Int): Boolean =
    address == 0uL || size == 0 || UserMemory(process.addressSpace, address).copyToUser(value)

private data class SelectTimeout(val seconds: Long, val nanoseconds: Long) {
    val isZero: Boolean get() = seconds == 0L && nanoseconds == 0L
}

private data class PollTimeout(val deadline: ULong?, val immediate: Boolean) {
    fun expired(): Boolean = deadline != null && TscClock.nanoTime() >= deadline

    companion object {
        val INFINITE = PollTimeout(null, immediate = false)
        val IMMEDIATE = PollTimeout(null, immediate = true)
    }
}

private fun readPselectTimeout(process: Process, address: ULong): VfsResult<SelectTimeout> {
    val bytes = UserMemory(process.addressSpace, address).copyFromUser(TimeSpec.NATIVE_SIZE)
        ?: return VfsResult.Err(VfsError.FAULT)
    val value = TimeSpec(0, 0)
    if (!value.updateFromNativeBytes(bytes)) return VfsResult.Err(VfsError.FAULT)
    if (value.sec < 0 || value.nsec !in 0 until 1_000_000_000L) {
        return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    }
    return VfsResult.Ok(SelectTimeout(value.sec, value.nsec))
}

private fun readPselectSignalMask(
    process: Process,
    address: ULong,
    currentMask: ULong,
): VfsResult<ULong> {
    val descriptor = UserMemory(process.addressSpace, address).copyFromUser(ULong.SIZE_BYTES * 2)
        ?: return VfsResult.Err(VfsError.FAULT)
    val input = LittleEndianBuffer(descriptor)
    val signalSet = input.readU64(0)
    val size = input.readU64(ULong.SIZE_BYTES)
    if (signalSet == 0uL) return VfsResult.Ok(currentMask)
    if (size != ULong.SIZE_BYTES.toULong()) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
    val mask = UserMemory(process.addressSpace, signalSet).copyFromUser(ULong.SIZE_BYTES)
        ?: return VfsResult.Err(VfsError.FAULT)
    return VfsResult.Ok(LittleEndianBuffer(mask).readU64(0) and Signal.BLOCKABLE_MASK)
}

private fun timeoutDeadline(timeout: SelectTimeout): ULong {
    val seconds = timeout.seconds.toULong()
    val duration = if (seconds > (ULong.MAX_VALUE - timeout.nanoseconds.toULong()) / 1_000_000_000uL) {
        ULong.MAX_VALUE
    } else {
        seconds * 1_000_000_000uL + timeout.nanoseconds.toULong()
    }
    val now = TscClock.nanoTime()
    return if (duration > ULong.MAX_VALUE - now) ULong.MAX_VALUE else now + duration
}

private fun scanSelectDescriptors(
    process: Process,
    nfds: Int,
    read: ByteArray,
    write: ByteArray,
    except: ByteArray,
): Int {
    var ready = 0
    for (fd in 0 until nfds) {
        val requested = (if (read.isSet(fd)) PollEvents.NORMAL_INPUT else 0) or
            (if (write.isSet(fd)) PollEvents.NORMAL_OUTPUT else 0) or
            (if (except.isSet(fd)) PollEvents.POLLPRI else 0)
        if (requested == 0) continue
        val file = process.fdTable.acquire(fd) ?: return -Errno.EBADF
        val events = try { file.poll(requested).toInt() } finally { file.release() }
        if (events < 0) return events
        val readable = events and PollEvents.NORMAL_INPUT != 0
        val writable = events and PollEvents.NORMAL_OUTPUT != 0
        val exceptional = events and PollEvents.POLLPRI != 0
        read.set(fd, readable)
        write.set(fd, writable)
        except.set(fd, exceptional)
        if (readable) ready++
        if (writable) ready++
        if (exceptional) ready++
    }
    return ready
}

private fun ByteArray.isSet(fd: Int): Boolean =
    this[fd / Byte.SIZE_BITS].toInt() and (1 shl (fd % Byte.SIZE_BITS)) != 0

private fun ByteArray.set(fd: Int, value: Boolean) {
    val index = fd / Byte.SIZE_BITS
    val mask = 1 shl (fd % Byte.SIZE_BITS)
    this[index] = if (value) (this[index].toInt() or mask).toByte()
    else (this[index].toInt() and mask.inv()).toByte()
}

private fun scanPollDescriptors(
    process: Process,
    descriptors: ByteArray,
    count: Int,
): Int {
    var ready = 0
    val input = LittleEndianBuffer(descriptors)
    repeat(count) { index ->
        val offset = index * POLL_FD_SIZE
        val fd = input.readU32(offset).toInt()
        val requested = input.readU16(offset + Int.SIZE_BYTES).toInt()
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

        input.writeU16(offset + Int.SIZE_BYTES + Short.SIZE_BYTES, returned.toUShort())
        if (returned != 0) {
            ready++
        }
    }
    return ready
}
