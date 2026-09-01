@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.acpi.fadt.Fadt
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.syscall.Syscall.userMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PtraceRegisters

private const val REBOOT_MAGIC1 = 0xfee1deadUL
private const val REBOOT_MAGIC2 = 672274793UL
private const val REBOOT_MAGIC2A = 85072278UL
private const val REBOOT_MAGIC2B = 369367448UL
private const val REBOOT_MAGIC2C = 537993216UL

private const val REBOOT_CMD_RESTART = 0x01234567UL
private const val REBOOT_CMD_HALT = 0xCDEF0123UL
private const val REBOOT_CMD_CAD_ON = 0x89ABCDEFUL
private const val REBOOT_CMD_CAD_OFF = 0x00000000UL
private const val REBOOT_CMD_POWER_OFF = 0x4321FEDCUL
private const val REBOOT_CMD_RESTART2 = 0xA1B2C3D4UL
private const val REBOOT_CMD_SW_SUSPEND = 0xD000FCE2UL
private const val REBOOT_CMD_KEXEC = 0x45584543UL

private const val GETRANDOM_MAX = 0x1ff_ffffuL
private const val GETRANDOM_FLAGS = 0x7uL
private const val RANDOM_CHUNK_SIZE = 4096

private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
private const val NANOSECONDS_PER_MICROSECOND = 1_000uL

private enum class ClockId(val value: ULong, private val realtime: Boolean = false) {
    REALTIME(0uL, realtime = true),
    MONOTONIC(1uL),
    MONOTONIC_RAW(4uL),
    REALTIME_COARSE(5uL, realtime = true),
    MONOTONIC_COARSE(6uL),
    BOOTTIME(7uL),
    ;

    fun read(): TimeSpec = if (realtime) {
        RealtimeClock.now().let { TimeSpec(it.seconds, it.nanoseconds.toLong()) }
    } else {
        TscClock.nanoTime().let {
            TimeSpec(
                sec = (it / NANOSECONDS_PER_SECOND).toLong(),
                nsec = (it % NANOSECONDS_PER_SECOND).toLong(),
            )
        }
    }

    companion object {
        fun from(value: ULong): ClockId? = entries.firstOrNull { it.value == value }
    }
}

private class LinuxTimeval(val seconds: Long, val microseconds: Long) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(Long.SIZE_BYTES * 2).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, seconds.toULong())
            writeU64(Long.SIZE_BYTES, microseconds.toULong())
        }
    }
}

private data class SysInfo(
    val uptime: Long,
    val loads: ULongArray,
    val totalRam: ULong,
    val freeRam: ULong,
    val sharedRam: ULong,
    val bufferRam: ULong,
    val totalSwap: ULong,
    val freeSwap: ULong,
    val processes: UShort,
    val totalHigh: ULong,
    val freeHigh: ULong,
    val memoryUnit: UInt,
) : NativeStruct {
    init {
        require(loads.size == LOAD_COUNT) { "sysinfo requires exactly $LOAD_COUNT load averages" }
    }

    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(UPTIME_OFFSET, uptime.toULong())
            loads.forEachIndexed { index, load ->
                writeU64(LOADS_OFFSET + index * ULong.SIZE_BYTES, load)
            }
            writeU64(TOTAL_RAM_OFFSET, totalRam)
            writeU64(FREE_RAM_OFFSET, freeRam)
            writeU64(SHARED_RAM_OFFSET, sharedRam)
            writeU64(BUFFER_RAM_OFFSET, bufferRam)
            writeU64(TOTAL_SWAP_OFFSET, totalSwap)
            writeU64(FREE_SWAP_OFFSET, freeSwap)
            writeU16(PROCESSES_OFFSET, processes)
            writeU64(TOTAL_HIGH_OFFSET, totalHigh)
            writeU64(FREE_HIGH_OFFSET, freeHigh)
            writeU32(MEMORY_UNIT_OFFSET, memoryUnit)
        }
    }

    companion object {
        const val LOAD_COUNT = 3
        private const val UPTIME_OFFSET = 0
        private const val LOADS_OFFSET = UPTIME_OFFSET + Long.SIZE_BYTES
        private const val TOTAL_RAM_OFFSET = LOADS_OFFSET + LOAD_COUNT * ULong.SIZE_BYTES
        private const val FREE_RAM_OFFSET = TOTAL_RAM_OFFSET + ULong.SIZE_BYTES
        private const val SHARED_RAM_OFFSET = FREE_RAM_OFFSET + ULong.SIZE_BYTES
        private const val BUFFER_RAM_OFFSET = SHARED_RAM_OFFSET + ULong.SIZE_BYTES
        private const val TOTAL_SWAP_OFFSET = BUFFER_RAM_OFFSET + ULong.SIZE_BYTES
        private const val FREE_SWAP_OFFSET = TOTAL_SWAP_OFFSET + ULong.SIZE_BYTES
        private const val PROCESSES_OFFSET = FREE_SWAP_OFFSET + ULong.SIZE_BYTES
        private const val TOTAL_HIGH_OFFSET = PROCESSES_OFFSET + ULong.SIZE_BYTES
        private const val FREE_HIGH_OFFSET = TOTAL_HIGH_OFFSET + ULong.SIZE_BYTES
        private const val MEMORY_UNIT_OFFSET = FREE_HIGH_OFFSET + ULong.SIZE_BYTES
        const val NATIVE_SIZE = MEMORY_UNIT_OFFSET + ULong.SIZE_BYTES
    }
}

internal data class TimeSpec(var sec: Long, var nsec: Long) : NativeStruct {
    override fun toNativeBytes(): ByteArray =
        ByteArray(NATIVE_SIZE).also { buffer ->
            LittleEndianBuffer(buffer).apply {
                writeU64(SEC_OFFSET, sec.toULong())
                writeU64(NSEC_OFFSET, nsec.toULong())
            }
        }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) {
            return false
        }

        val input = LittleEndianBuffer(buffer)
        val updatedSec = input.readU64(SEC_OFFSET).toLong()
        val updatedNsec = input.readU64(NSEC_OFFSET).toLong()

        sec = updatedSec
        nsec = updatedNsec
        return true
    }

    companion object {
        private const val SEC_OFFSET = 0
        private const val NSEC_OFFSET = SEC_OFFSET + Long.SIZE_BYTES
        const val NATIVE_SIZE = Long.SIZE_BYTES * 2
    }
}

internal fun reboot(regs: PtraceRegisters, process: Process): Long {
    val magic1 = regs[PtraceRegisters.IDX_RDI]
    val magic2 = regs[PtraceRegisters.IDX_RSI]
    val command = regs[PtraceRegisters.IDX_RDX]

    if (magic1 != REBOOT_MAGIC1 || magic2 != REBOOT_MAGIC2) {
        return errno(Errno.EINVAL)
    }

    return when (command) {
        REBOOT_CMD_POWER_OFF -> {
            Fadt.shutdown()
            errno(Errno.EOK)
        }

        REBOOT_CMD_RESTART2 or REBOOT_CMD_RESTART -> {
            Fadt.reboot()
            errno(Errno.EOK)
        }

        REBOOT_CMD_CAD_OFF or REBOOT_CMD_CAD_ON -> errno(Errno.ENOSYS)
        else -> errno(Errno.EINVAL)
    }
}

internal fun time(regs: PtraceRegisters, process: Process): Long {
    if (!TscClock.isReady) return errno(Errno.EIO)
    val seconds = RealtimeClock.now().seconds
    val outputAddress = regs[PtraceRegisters.IDX_RDI]
    if (outputAddress != 0uL) {
        val result = Syscall.copyWordToUser(process, outputAddress, seconds.toULong())
        if (result != 0L) return result
    }
    return seconds
}

internal fun clockGetTime(regs: PtraceRegisters, process: Process): Long {
    val clock = ClockId.from(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
    if (!TscClock.isReady) return errno(Errno.EIO)
    return if (UserMemory(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
        ).copyToUser(clock.read().toNativeBytes())
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun getTimeOfDay(regs: PtraceRegisters, process: Process): Long {
    if (!TscClock.isReady) return errno(Errno.EIO)
    val now = RealtimeClock.now()
    val time = LinuxTimeval(
        seconds = now.seconds,
        microseconds = (now.nanoseconds.toULong() / NANOSECONDS_PER_MICROSECOND).toLong(),
    )
    val timeAddress = regs[PtraceRegisters.IDX_RDI]
    if (timeAddress != 0uL &&
        !UserMemory(process.addressSpace, timeAddress).copyToUser(time.toNativeBytes())
    ) {
        return errno(Errno.EFAULT)
    }
    val timezoneAddress = regs[PtraceRegisters.IDX_RSI]
    return if (timezoneAddress == 0uL ||
        UserMemory(process.addressSpace, timezoneAddress).copyToUser(ByteArray(8))
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun nanoSleep(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val bytes = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
        .copyFromUser(TimeSpec.NATIVE_SIZE)
        ?: return errno(Errno.EFAULT)
    val requested = TimeSpec(0, 0)
    if (!requested.updateFromNativeBytes(bytes) ||
        requested.sec < 0 || requested.nsec !in 0 until NANOSECONDS_PER_SECOND.toLong()
    ) {
        return errno(Errno.EINVAL)
    }
    if (!TscClock.isReady) return errno(Errno.EIO)

    val seconds = requested.sec.toULong()
    val nanoseconds = requested.nsec.toULong()
    val duration = if (seconds > (ULong.MAX_VALUE - nanoseconds) / NANOSECONDS_PER_SECOND) {
        ULong.MAX_VALUE
    } else {
        seconds * NANOSECONDS_PER_SECOND + nanoseconds
    }
    val start = TscClock.nanoTime()
    val deadline = if (duration > ULong.MAX_VALUE - start) ULong.MAX_VALUE else start + duration
    while (TscClock.nanoTime() < deadline) {
        if (thread.hasPendingSignal()) {
            val remaining = deadline - TscClock.nanoTime().coerceAtMost(deadline)
            val remainingAddress = regs[PtraceRegisters.IDX_RSI]
            if (remainingAddress != 0uL &&
                !UserMemory(process.addressSpace, remainingAddress).copyToUser(
                    TimeSpec(
                        sec = (remaining / NANOSECONDS_PER_SECOND).toLong(),
                        nsec = (remaining % NANOSECONDS_PER_SECOND).toLong(),
                    ).toNativeBytes(),
                )
            ) {
                return errno(Errno.EFAULT)
            }
            return errno(Errno.EINTR)
        }
        Scheduler.yieldCurrent()
        bridge.wait_for_interrupt()
    }
    return 0L
}

internal fun getRandom(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_RDX]
    if (flags and GETRANDOM_FLAGS.inv() != 0uL) return errno(Errno.EINVAL)

    val requested = minOf(regs[PtraceRegisters.IDX_RSI], GETRANDOM_MAX)
    if (requested == 0uL) return 0L
    val buffer = ByteArray(minOf(requested, RANDOM_CHUNK_SIZE.toULong()).toInt())
    var transferred = 0uL
    while (transferred < requested) {
        val count = minOf(requested - transferred, buffer.size.toULong()).toInt()
        val output = userMemory(process, regs[PtraceRegisters.IDX_RDI], transferred)
            ?: return partialOrError(transferred, Errno.EFAULT)
        KernelRandom.fill(buffer, size = count, salt = process.id.toULong() xor transferred)
        if (!output.copyToUser(buffer, size = count)) {
            return partialOrError(transferred, Errno.EFAULT)
        }
        transferred += count.toULong()
    }
    return transferred.toLong()
}

internal fun sysInfo(regs: PtraceRegisters, process: Process): Long {
    val userBuffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
    val physical = BuddyFrameAllocator.statistics()
    val info = SysInfo(
        uptime = if (TscClock.isReady) {
            (TscClock.nanoTime() / NANOSECONDS_PER_SECOND).toLong()
        } else {
            0L
        },
        loads = ULongArray(SysInfo.LOAD_COUNT),
        totalRam = physical.totalBytes,
        freeRam = physical.freeBytes,
        sharedRam = 0uL,
        bufferRam = 0uL,
        totalSwap = 0uL,
        freeSwap = 0uL,
        processes = ProcessManager.snapshotProcesses().size.toUShort(),
        totalHigh = 0uL,
        freeHigh = 0uL,
        memoryUnit = 1u,
    ).toNativeBytes()
    return if (userBuffer.copyToUser(info)) errno(Errno.EOK) else errno(Errno.EFAULT)
}

internal fun clockGetRes(regs: PtraceRegisters, process: Process): Long {
    ClockId.from(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
    val address = regs[PtraceRegisters.IDX_RSI]
    if (address == 0uL) return 0L
    val resolution = TimeSpec(sec = 0, nsec = 1).toNativeBytes()
    return if (UserMemory(process.addressSpace, address).copyToUser(resolution)) 0L
    else errno(Errno.EFAULT)
}
