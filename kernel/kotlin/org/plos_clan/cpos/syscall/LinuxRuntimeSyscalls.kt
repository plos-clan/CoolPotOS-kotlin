@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.ResourceLimit
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PtraceRegisters

private const val CLOCK_REALTIME = 0
private const val CLOCK_MONOTONIC = 1
private const val CLOCK_MONOTONIC_RAW = 4
private const val CLOCK_REALTIME_COARSE = 5
private const val CLOCK_MONOTONIC_COARSE = 6
private const val CLOCK_BOOTTIME = 7

private const val SIG_BLOCK = 0
private const val SIG_UNBLOCK = 1
private const val SIG_SETMASK = 2
private const val SIGNAL_COUNT = 64
private const val SIGNAL_SET_SIZE = ULong.SIZE_BYTES
private const val SIGACTION_SIZE = 32

private const val AT_RANDOM_BYTES = 0x7fff_f000uL
private const val GRND_NONBLOCK = 0x1uL
private const val GRND_RANDOM = 0x2uL
private const val GRND_INSECURE = 0x4uL

private const val RSEQ_UNREGISTER = 1uL
private const val RSEQ_SIZE = 32uL
private const val ROBUST_LIST_SIZE = 24uL

private const val NS_PER_SECOND = 1_000_000_000uL
private const val NS_PER_MICROSECOND = 1_000uL

private class TimeValue(val seconds: Long, val microseconds: Long) : NativeStruct() {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        putU64LE(buffer, 0, seconds.toULong())
        putU64LE(buffer, Long.SIZE_BYTES, microseconds.toULong())
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean = false

    companion object {
        const val NATIVE_SIZE = Long.SIZE_BYTES * 2
    }
}

private class ResourceLimitValue(val limit: ResourceLimit) : NativeStruct() {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        putU64LE(buffer, 0, limit.soft)
        putU64LE(buffer, ULong.SIZE_BYTES, limit.hard)
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean = false

    companion object {
        const val NATIVE_SIZE = ULong.SIZE_BYTES * 2
    }
}

object LinuxRuntimeSyscalls {
    fun time(regs: PtraceRegisters, process: Process): Long {
        val seconds = (clockNow() ?: return Syscall.errno(Errno.EIO)) / NS_PER_SECOND
        if (regs[PtraceRegisters.IDX_RDI] != 0uL &&
            Syscall.copyWordToUser(process, regs[PtraceRegisters.IDX_RDI], seconds) != 0L
        ) return Syscall.errno(Errno.EFAULT)
        return seconds.toLong()
    }

    fun clockGettime(regs: PtraceRegisters, process: Process): Long {
        val clockId = regs[PtraceRegisters.IDX_RDI]
        if (clockId > Int.MAX_VALUE.toULong() || !supportedClock(clockId.toInt())) {
            return Syscall.errno(Errno.EINVAL)
        }
        val now = clockNow() ?: return Syscall.errno(Errno.EIO)
        return copyToUser(
            process,
            regs[PtraceRegisters.IDX_RSI],
            TimeSpec(
                sec = (now / NS_PER_SECOND).toLong(),
                nsec = (now % NS_PER_SECOND).toLong(),
            ).toNativeBytes(),
        )
    }

    fun gettimeofday(regs: PtraceRegisters, process: Process): Long {
        val now = clockNow() ?: return Syscall.errno(Errno.EIO)
        val time = TimeValue(
            seconds = (now / NS_PER_SECOND).toLong(),
            microseconds = ((now % NS_PER_SECOND) / NS_PER_MICROSECOND).toLong(),
        )
        val result = if (regs[PtraceRegisters.IDX_RDI] == 0uL) {
            0L
        } else {
            copyToUser(process, regs[PtraceRegisters.IDX_RDI], time.toNativeBytes())
        }
        if (result < 0 || regs[PtraceRegisters.IDX_RSI] == 0uL) {
            return result
        }
        return copyToUser(process, regs[PtraceRegisters.IDX_RSI], ByteArray(8))
    }

    fun nanoSleep(regs: PtraceRegisters, process: Process): Long {
        val source = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
        val bytes = source.copyFromUser(TimeSpec.NATIVE_SIZE) ?: return Syscall.errno(Errno.EFAULT)
        val time = TimeSpec(0, 0).also { require(it.updateFromNativeBytes(bytes)) }
        if (time.sec < 0 || time.nsec !in 0 until NS_PER_SECOND.toLong()) {
            return Syscall.errno(Errno.EINVAL)
        }
        val duration = time.sec.toULong() * NS_PER_SECOND + time.nsec.toULong()
        val start = clockNow() ?: return Syscall.errno(Errno.EIO)
        val deadline = if (duration > ULong.MAX_VALUE - start) ULong.MAX_VALUE else start + duration
        while ((clockNow() ?: deadline) < deadline) {
            bridge.cpu_relax()
        }
        return 0L
    }

    fun getrandom(regs: PtraceRegisters, process: Process): Long {
        val flags = regs[PtraceRegisters.IDX_RDX]
        val supportedFlags = GRND_NONBLOCK or GRND_RANDOM or GRND_INSECURE
        if (flags and supportedFlags.inv() != 0uL) {
            return Syscall.errno(Errno.EINVAL)
        }
        val requested = minOf(regs[PtraceRegisters.IDX_RSI], AT_RANDOM_BYTES)
        if (requested == 0uL) {
            return 0L
        }
        val buffer = ByteArray(minOf(requested, 4096uL).toInt())
        var transferred = 0uL
        while (transferred < requested) {
            val count = minOf(requested - transferred, buffer.size.toULong()).toInt()
            val user = Syscall.userMemory(process, regs[PtraceRegisters.IDX_RDI], transferred)
                ?: return Syscall.partialOrError(transferred, Errno.EFAULT)
            KernelRandom.fill(buffer, size = count, salt = process.id.toULong() xor transferred)
            if (!user.copyToUser(buffer, size = count)) {
                return Syscall.partialOrError(transferred, Errno.EFAULT)
            }
            transferred += count.toULong()
        }
        return transferred.toLong()
    }

    fun setRobustList(regs: PtraceRegisters, process: Process): Long {
        val length = regs[PtraceRegisters.IDX_RSI]
        if (length != ROBUST_LIST_SIZE) {
            return Syscall.errno(Errno.EINVAL)
        }
        val head = regs[PtraceRegisters.IDX_RDI]
        if (head >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return Syscall.errno(Errno.EFAULT)
        }
        val thread = ProcessManager.currentThread() ?: return Syscall.errno(Errno.ESRCH)
        thread.robustListHead = head
        return 0L
    }

    fun rseq(regs: PtraceRegisters, process: Process): Long =
        Syscall.errno(Errno.ENOSYS)

    fun prlimit64(regs: PtraceRegisters, process: Process): Long {
        val pid = regs[PtraceRegisters.IDX_RDI].toLong()
        val target = if (pid == 0L) {
            process
        } else {
            if (pid !in 1..Int.MAX_VALUE.toLong()) return Syscall.errno(Errno.ESRCH)
            ProcessManager.findProcess(pid.toInt()) ?: return Syscall.errno(Errno.ESRCH)
        }
        val resource = ProcessResource.from(regs[PtraceRegisters.IDX_RSI].toInt())
            ?: return Syscall.errno(Errno.EINVAL)
        val replacement = if (regs[PtraceRegisters.IDX_RDX] == 0uL) {
            null
        } else {
            val bytes = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDX])
                .copyFromUser(ResourceLimitValue.NATIVE_SIZE)
                ?: return Syscall.errno(Errno.EFAULT)
            val soft = bytes.readU64LE(0)
            val hard = bytes.readU64LE(ULong.SIZE_BYTES)
            if (soft > hard) return Syscall.errno(Errno.EINVAL)
            ResourceLimit(soft, hard)
        }
        val current = target.resourceLimits.get(resource)
        if (regs[PtraceRegisters.IDX_R10] != 0uL &&
            !UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_R10]).copyToUser(
                ResourceLimitValue(current).toNativeBytes(),
            )
        ) {
            return Syscall.errno(Errno.EFAULT)
        }
        replacement?.let { target.resourceLimits.set(resource, it) }
        return 0L
    }

    fun rtSigaction(regs: PtraceRegisters, process: Process): Long {
        val signal = regs[PtraceRegisters.IDX_RDI]
        val size = regs[PtraceRegisters.IDX_R10]
        if (signal !in 1uL..SIGNAL_COUNT.toULong() || size != SIGNAL_SET_SIZE.toULong()) {
            return Syscall.errno(Errno.EINVAL)
        }
        val index = signal.toInt() - 1
        val action = if (regs[PtraceRegisters.IDX_RSI] == 0uL) {
            null
        } else {
            UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI]).copyFromUser(SIGACTION_SIZE)
                ?: return Syscall.errno(Errno.EFAULT)
        }
        val oldAddress = regs[PtraceRegisters.IDX_RDX]
        if (oldAddress != 0uL) {
            val old = process.signalActions[index] ?: ByteArray(SIGACTION_SIZE)
            if (!UserMemory(process.addressSpace, oldAddress).copyToUser(old)) {
                return Syscall.errno(Errno.EFAULT)
            }
        }
        process.signalActions[index] = action
        return 0L
    }

    fun rtSigprocmask(regs: PtraceRegisters, process: Process): Long {
        val size = regs[PtraceRegisters.IDX_R10]
        if (size != SIGNAL_SET_SIZE.toULong() || regs[PtraceRegisters.IDX_RDI] > SIG_SETMASK.toULong()) {
            return Syscall.errno(Errno.EINVAL)
        }
        val oldAddress = regs[PtraceRegisters.IDX_RDX]
        if (oldAddress != 0uL && Syscall.copyWordToUser(process, oldAddress, process.signalMask) != 0L) {
            return Syscall.errno(Errno.EFAULT)
        }
        val setAddress = regs[PtraceRegisters.IDX_RSI]
        if (setAddress == 0uL) return 0L
        val set = UserMemory(process.addressSpace, setAddress).copyFromUser(ULong.SIZE_BYTES)
            ?: return Syscall.errno(Errno.EFAULT)
        val mask = set.readU64LE(0)
        process.signalMask = when (regs[PtraceRegisters.IDX_RDI].toInt()) {
            SIG_BLOCK -> process.signalMask or mask
            SIG_UNBLOCK -> process.signalMask and mask.inv()
            SIG_SETMASK -> mask
            else -> return Syscall.errno(Errno.EINVAL)
        }
        return 0L
    }

    private fun supportedClock(clockId: Int): Boolean = clockId in setOf(
        CLOCK_REALTIME,
        CLOCK_MONOTONIC,
        CLOCK_MONOTONIC_RAW,
        CLOCK_REALTIME_COARSE,
        CLOCK_MONOTONIC_COARSE,
        CLOCK_BOOTTIME,
    )

    private fun clockNow(): ULong? = TscClock.nanoTime().takeIf { TscClock.isReady }

    private fun copyToUser(process: Process, address: ULong, bytes: ByteArray): Long =
        if (UserMemory(process.addressSpace, address).copyToUser(bytes)) 0L else Syscall.errno(Errno.EFAULT)

    private fun ByteArray.readU64LE(offset: Int): ULong {
        var value = 0uL
        repeat(ULong.SIZE_BYTES) { index ->
            value = value or (this[offset + index].toUByte().toULong() shl (index * Byte.SIZE_BITS))
        }
        return value
    }
}
