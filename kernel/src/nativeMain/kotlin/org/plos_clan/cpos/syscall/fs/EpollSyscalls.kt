@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.Epoll
import org.plos_clan.cpos.fs.vfs.EpollControlOperation
import org.plos_clan.cpos.fs.vfs.EpollEvent
import org.plos_clan.cpos.fs.vfs.EpollEvents
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.IoScratch
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.tasks.PollWait
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal object EpollSyscalls {
    private const val EVENT_SIZE = UInt.SIZE_BYTES + ULong.SIZE_BYTES
    private const val EPOLL_CLOEXEC = 0x0008_0000uL

    fun create(regs: PtraceRegisters, process: Process): Long {
        val size = regs[PtraceRegisters.IDX_RDI]
        if (size == 0uL || size > Int.MAX_VALUE.toULong()) return errno(Errno.EINVAL)
        return create(process, closeOnExec = false)
    }

    fun create1(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDI]
        if (flags and EPOLL_CLOEXEC.inv() != 0uL) return errno(Errno.EINVAL)
        return create(process, closeOnExec = flags and EPOLL_CLOEXEC != 0uL)
    }

    fun control(regs: PtraceRegisters, process: Process): Long {
        val epollDescriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val operation = when (regs[PtraceRegisters.IDX_RSI].toInt()) {
            1 -> EpollControlOperation.ADD
            2 -> EpollControlOperation.DELETE
            3 -> EpollControlOperation.MODIFY
            else -> return errno(Errno.EINVAL)
        }
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDX])
            ?: return errno(Errno.EBADF)
        val epollFile = process.fdTable.acquire(epollDescriptor)
            ?: return errno(Errno.EBADF)
        val target = process.fdTable.acquire(descriptor) ?: run {
            epollFile.release()
            return errno(Errno.EBADF)
        }
        try {
            val epoll = epollFile.backend as? Epoll ?: return errno(Errno.EINVAL)
            if (epollFile === target) return errno(Errno.EINVAL)
            when (target.inode.type) {
                InodeType.REGULAR,
                InodeType.DIRECTORY,
                InodeType.SYMLINK,
                InodeType.BLOCK_DEVICE,
                -> if (!target.backend.supportsEpoll) return errno(Errno.EPERM)
                else -> Unit
            }

            val event = if (operation == EpollControlOperation.DELETE) {
                null
            } else {
                readEvent(process, regs[PtraceRegisters.IDX_R10])
                    ?: return errno(Errno.EFAULT)
            }
            if (event != null && !validEvent(operation, target.inode.type, event.events)) {
                return errno(Errno.EINVAL)
            }
            if (operation == EpollControlOperation.ADD &&
                target.poll(process.vfsOperationContext, 0) < 0
            ) return errno(Errno.EPERM)
            return when (val result = epoll.control(descriptor, target, operation, event)) {
                is VfsResult.Ok -> 0L
                is VfsResult.Err -> errno(result.error.errno)
            }
        } finally {
            target.release()
            epollFile.release()
        }
    }

    fun wait(regs: PtraceRegisters, process: Process): Long {
        val timeout = PollTimeout.fromMilliseconds(regs[PtraceRegisters.IDX_R10].toInt())
            ?: return errno(Errno.EIO)
        return wait(regs, process, timeout, 0uL, 0uL)
    }

    fun pwait(regs: PtraceRegisters, process: Process): Long {
        val timeout = PollTimeout.fromMilliseconds(regs[PtraceRegisters.IDX_R10].toInt())
            ?: return errno(Errno.EIO)
        return wait(
            regs,
            process,
            timeout,
            regs[PtraceRegisters.IDX_R8],
            regs[PtraceRegisters.IDX_R9],
        )
    }

    fun pwait2(regs: PtraceRegisters, process: Process): Long {
        val timeoutAddress = regs[PtraceRegisters.IDX_R10]
        val timeout = PollTimeout.read(process, timeoutAddress) { return errno(it) }
        return wait(
            regs,
            process,
            timeout,
            signalMaskAddress = regs[PtraceRegisters.IDX_R8],
            signalMaskSize = regs[PtraceRegisters.IDX_R9],
        )
    }

    private fun create(process: Process, closeOnExec: Boolean): Long {
        val context = process.context ?: return errno(Errno.ENOENT)
        val file = when (val result = FileSystemManager.vfs.createEpoll(
            process.vfsOperationContext,
            context,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val flags = if (closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
        return process.fdTable.install(file, flags)?.toLong() ?: run {
            file.release()
            errno(Errno.EMFILE)
        }
    }

    private fun wait(
        regs: PtraceRegisters,
        process: Process,
        timeout: PollTimeout,
        signalMaskAddress: ULong,
        signalMaskSize: ULong,
    ): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val maximumValue = regs[PtraceRegisters.IDX_RDX]
        if (maximumValue == 0uL || maximumValue > (Int.MAX_VALUE / EVENT_SIZE).toULong()) {
            return errno(Errno.EINVAL)
        }
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        var transferred = false
        try {
            val epoll = file.backend as? Epoll ?: return errno(Errno.EINVAL)
            val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
            val mask = PollTimeout.readMask(process, signalMaskAddress, signalMaskSize) {
                return errno(it)
            }
            val caller = process.vfsOperationContext
            val output = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
            val ready = timeout.wait(regs, thread, mask, openWait = {
                val pending = PollWait(thread, file::release)
                transferred = true
                pending
            }) { subscription ->
                epoll.collectInto(caller, maximumValue.toInt(), output, subscription)
            }
            return ready.toLong()
        } catch (_: OutOfMemoryError) {
            return errno(Errno.ENOMEM)
        } finally {
            if (!transferred) file.release()
        }
    }

    private fun Epoll.collectInto(
        caller: VfsOperationContext,
        maximum: Int,
        output: UserMemory,
        subscription: PollSubscription?,
    ): Int = IoScratch.borrow { scratch ->
        val count = collect(caller, maximum, subscription) { index, events, data ->
            val offset = index * EVENT_SIZE
            val bytes = scratch.reserve(offset + EVENT_SIZE)
            val record = LittleEndianBuffer(bytes)
            record.writeU32(offset, events)
            record.writeU64(offset + UInt.SIZE_BYTES, data)
        }
        if (count == 0 || output.writeFrom(scratch.bytes, count * EVENT_SIZE)) count else -Errno.EFAULT
    }

    private fun readEvent(process: Process, address: ULong): EpollEvent? {
        val source = UserMemory(process.addressSpace, address)
        return source.readRecord(EVENT_SIZE) { EpollEvent(it.readU32(0), it.readU64(UInt.SIZE_BYTES)) }
    }

    private fun validEvent(
        operation: EpollControlOperation,
        targetType: InodeType,
        events: UInt,
    ): Boolean {
        if (events and EpollEvents.SUPPORTED.inv() != 0u) return false
        if (events and EpollEvents.EXCLUSIVE == 0u) return true
        return operation == EpollControlOperation.ADD && targetType != InodeType.EPOLL &&
            events and EpollEvents.ONE_SHOT == 0u &&
            events and EpollEvents.EXCLUSIVE_SUPPORTED.inv() == 0u
    }
}
