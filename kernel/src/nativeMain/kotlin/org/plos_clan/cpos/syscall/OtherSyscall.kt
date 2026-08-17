@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import KERNEL_NAME
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.acpi.Fadt
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.partialOrError
import org.plos_clan.cpos.syscall.Syscall.userMemory
import org.plos_clan.cpos.tasks.Process
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

private const val MAX_CLOCK_ID = 7
private const val SUPPORTED_CLOCKS = 0xf3u

private const val GETRANDOM_MAX = 0x1ff_ffffuL
private const val GETRANDOM_FLAGS = 0x7uL
private const val RANDOM_CHUNK_SIZE = 4096

private const val NANOSECONDS_PER_SECOND = 1_000_000_000uL
private const val NANOSECONDS_PER_MICROSECOND = 1_000uL

private class LinuxTimeval(val seconds: Long, val microseconds: Long) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(Long.SIZE_BYTES * 2).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, seconds.toULong())
            writeU64(Long.SIZE_BYTES, microseconds.toULong())
        }
    }
}

private data class UtsName(
    val sysname: String,
    val nodename: String,
    val release: String,
    val version: String,
    val machine: String,
    val domainname: String,
) : NativeStruct {
    init {
        fields().forEach { (name, value) ->
            require('\u0000' !in value) { "$name must not contain NUL" }
            require(value.encodeToByteArray().size < FIELD_SIZE) {
                "$name must fit in ${FIELD_SIZE - 1} UTF-8 bytes"
            }
        }
    }

    override fun toNativeBytes(): ByteArray =
        ByteArray(NATIVE_SIZE).also { buffer ->
            fields().forEachIndexed { index, (_, value) ->
                value.encodeToByteArray().copyInto(
                    destination = buffer,
                    destinationOffset = index * FIELD_SIZE,
                )
            }
        }

    private fun fields(): List<Pair<String, String>> = listOf(
        "sysname" to sysname,
        "nodename" to nodename,
        "release" to release,
        "version" to version,
        "machine" to machine,
        "domainname" to domainname,
    )

    companion object {
        const val FIELD_SIZE = 65
        const val FIELD_COUNT = 6
        const val NATIVE_SIZE = FIELD_SIZE * FIELD_COUNT
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

private val UTS_NAME = UtsName(
    sysname = "CoolPotOS",
    nodename = "localhost",
    release = KERNEL_NAME,
    version = "v0.0.1",
    machine = "x86_64",
    domainname = "",
).toNativeBytes()

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

internal fun uname(regs: PtraceRegisters, process: Process): Long {
    val userBuffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
    return if (userBuffer.copyToUser(UTS_NAME)) 0L else errno(Errno.EFAULT)
}

internal fun time(regs: PtraceRegisters, process: Process): Long {
    if (!TscClock.isReady) return errno(Errno.EIO)
    val seconds = TscClock.nanoTime() / NANOSECONDS_PER_SECOND
    val outputAddress = regs[PtraceRegisters.IDX_RDI]
    if (outputAddress != 0uL) {
        val result = Syscall.copyWordToUser(process, outputAddress, seconds)
        if (result != 0L) return result
    }
    return seconds.toLong()
}

internal fun clockGetTime(regs: PtraceRegisters, process: Process): Long {
    val clockId = regs[PtraceRegisters.IDX_RDI]
    if (clockId > MAX_CLOCK_ID.toULong() ||
        SUPPORTED_CLOCKS and (1u shl clockId.toInt()) == 0u
    ) {
        return errno(Errno.EINVAL)
    }
    if (!TscClock.isReady) return errno(Errno.EIO)
    val now = TscClock.nanoTime()
    val value = TimeSpec(
        sec = (now / NANOSECONDS_PER_SECOND).toLong(),
        nsec = (now % NANOSECONDS_PER_SECOND).toLong(),
    )
    return if (UserMemory(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
        ).copyToUser(value.toNativeBytes())
    ) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun getTimeOfDay(regs: PtraceRegisters, process: Process): Long {
    if (!TscClock.isReady) return errno(Errno.EIO)
    val now = TscClock.nanoTime()
    val time = LinuxTimeval(
        seconds = (now / NANOSECONDS_PER_SECOND).toLong(),
        microseconds = ((now % NANOSECONDS_PER_SECOND) / NANOSECONDS_PER_MICROSECOND).toLong(),
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
