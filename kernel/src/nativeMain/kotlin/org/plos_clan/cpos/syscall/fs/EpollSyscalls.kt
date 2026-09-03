@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import bridge.wait_for_interrupt
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.Epoll
import org.plos_clan.cpos.fs.vfs.EpollControlOperation
import org.plos_clan.cpos.fs.vfs.EpollEvent
import org.plos_clan.cpos.fs.vfs.EpollEvents
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.SignalDelivery
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.syscall.TimeSpec
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal object EpollSyscalls {
    private const val EVENT_SIZE = UInt.SIZE_BYTES + ULong.SIZE_BYTES
    private const val EPOLL_CLOEXEC = 0x0008_0000uL
    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000uL

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
                -> return errno(Errno.EPERM)
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
        val timeout = WaitTimeout.fromMilliseconds(regs[PtraceRegisters.IDX_R10].toInt())
            ?: return errno(Errno.EIO)
        return wait(regs, process, timeout, 0uL, 0uL)
    }

    fun pwait(regs: PtraceRegisters, process: Process): Long {
        val timeout = WaitTimeout.fromMilliseconds(regs[PtraceRegisters.IDX_R10].toInt())
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
        val timeout = if (timeoutAddress == 0uL) {
            WaitTimeout.INFINITE
        } else {
            val bytes = UserMemory(process.addressSpace, timeoutAddress)
                .copyFromUser(TimeSpec.NATIVE_SIZE) ?: return errno(Errno.EFAULT)
            val value = TimeSpec(0, 0)
            if (!value.updateFromNativeBytes(bytes)) return errno(Errno.EFAULT)
            WaitTimeout.fromTimespec(value) ?: return errno(Errno.EINVAL)
        }
        if (!timeout.immediate && timeout.deadline != null && !TscClock.isReady) {
            return errno(Errno.EIO)
        }
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
        timeout: WaitTimeout,
        signalMaskAddress: ULong,
        signalMaskSize: ULong,
    ): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val maximumValue = regs[PtraceRegisters.IDX_RDX]
        if (maximumValue == 0uL || maximumValue > Int.MAX_VALUE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        val epoll = file.backend as? Epoll ?: run {
            file.release()
            return errno(Errno.EINVAL)
        }
        val thread = ProcessManager.currentThread() ?: run {
            file.release()
            return errno(Errno.ESRCH)
        }
        val signalMask = when {
            signalMaskAddress == 0uL -> null
            signalMaskSize != ULong.SIZE_BYTES.toULong() -> {
                file.release()
                return errno(Errno.EINVAL)
            }
            else -> UserMemory(process.addressSpace, signalMaskAddress)
                .copyFromUser(ULong.SIZE_BYTES)
                ?.let { LittleEndianBuffer(it).readU64(0) and Signal.BLOCKABLE_MASK }
                ?: run {
                    file.release()
                    return errno(Errno.EFAULT)
                }
        }
        val previousMask = signalMask?.let { thread.signals.replaceMask(it) }
        try {
            val events = ArrayList<EpollEvent>()
            while (true) {
                epoll.collect(process.vfsOperationContext, maximumValue.toInt(), events)
                if (events.isNotEmpty()) {
                    val bytes = ByteArray(events.size * EVENT_SIZE)
                    val output = LittleEndianBuffer(bytes)
                    events.forEachIndexed { index, event ->
                        val offset = index * EVENT_SIZE
                        output.writeU32(offset, event.events)
                        output.writeU64(offset + UInt.SIZE_BYTES, event.data)
                    }
                    return if (UserMemory(
                            process.addressSpace,
                            regs[PtraceRegisters.IDX_RSI],
                        ).copyToUser(bytes)
                    ) events.size.toLong() else errno(Errno.EFAULT)
                }
                if (timeout.immediate || timeout.expired()) return 0L
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
        } catch (_: OutOfMemoryError) {
            return errno(Errno.ENOMEM)
        } finally {
            if (previousMask != null && !regs.signalFrameInstalled) {
                thread.signals.mask = previousMask
            }
            file.release()
        }
    }

    private fun readEvent(process: Process, address: ULong): EpollEvent? {
        val bytes = UserMemory(process.addressSpace, address).copyFromUser(EVENT_SIZE) ?: return null
        val input = LittleEndianBuffer(bytes)
        return EpollEvent(input.readU32(0), input.readU64(UInt.SIZE_BYTES))
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

    private data class WaitTimeout(val deadline: ULong?, val immediate: Boolean) {
        fun expired(): Boolean = deadline != null && TscClock.nanoTime() >= deadline

        companion object {
            val INFINITE = WaitTimeout(null, immediate = false)
            private val IMMEDIATE = WaitTimeout(null, immediate = true)

            fun fromMilliseconds(milliseconds: Int): WaitTimeout? = when {
                milliseconds < 0 -> INFINITE
                milliseconds == 0 -> IMMEDIATE
                !TscClock.isReady -> null
                else -> after(milliseconds.toULong() * NANOSECONDS_PER_MILLISECOND)
            }

            fun fromTimespec(value: TimeSpec): WaitTimeout? {
                if (!value.isValidDuration) return null
                return if (value.isZeroDuration) IMMEDIATE else after(value.durationNanos)
            }

            private fun after(duration: ULong): WaitTimeout {
                if (!TscClock.isReady) return WaitTimeout(0uL, immediate = false)
                val now = TscClock.nanoTime()
                val deadline = if (duration > ULong.MAX_VALUE - now) ULong.MAX_VALUE
                else now + duration
                return WaitTimeout(deadline, immediate = false)
            }
        }
    }
}
