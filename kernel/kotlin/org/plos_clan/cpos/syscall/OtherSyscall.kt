@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import KERNEL_NAME
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.drivers.acpi.Fadt
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
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

data class UtsName(
    val sysname: String,
    val nodename: String,
    val release: String,
    val version: String,
    val machine: String,
    val domainname: String,
) : NativeStruct() {
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

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        TODO("Not yet implemented")
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

data class TimeSpec(var sec: Long, var nsec: Long) : NativeStruct() {
    override fun toNativeBytes(): ByteArray =
        ByteArray(NATIVE_SIZE).also { buffer ->
            putU64LE(buffer, SEC_OFFSET, sec.toULong())
            putU64LE(buffer, NSEC_OFFSET, nsec.toULong())
        }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) {
            return false
        }

        val updatedSec = getU64LE(buffer, SEC_OFFSET).toLong()
        val updatedNsec = getU64LE(buffer, NSEC_OFFSET).toLong()

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

fun sysReboot(regs: PtraceRegisters, process: Process): Long {
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

fun sysUname(regs: PtraceRegisters, process: Process): Long {
    val userBuffer = UserMemory(process.vma, regs[PtraceRegisters.IDX_RDI])

    val utsName = UtsName(
        sysname = "CoolPotOS",
        nodename = "localhost",
        release = KERNEL_NAME,
        version = "v0.0.1",
        machine = "x86_64",
        domainname = ""
    )

    return if (userBuffer.copyToUser(utsName.toNativeBytes())) errno(Errno.EFAULT)
    else errno(Errno.EOK)
}

fun sysNanoSleep(regs: PtraceRegisters, process: Process): Long {
    val time = UserMemory(process.vma, regs[PtraceRegisters.IDX_RDI])
    val destination = ByteArray(TimeSpec.NATIVE_SIZE)

    if (!time.copyFromUser(destination = destination, 0, TimeSpec.NATIVE_SIZE)) {
        return errno(Errno.EFAULT)
    }

    val timeSpec = TimeSpec(0, 0).also {
        spec -> spec.updateFromNativeBytes(destination)
    }

    if(timeSpec.nsec >= 1000000000L) return errno(Errno.EINVAL)

    val nsec = timeSpec.sec.toULong() * 1000000000UL + timeSpec.nsec.toULong()
    val end = Hpet.nanoTime() + nsec

    while (Hpet.nanoTime() >= end) {
        bridge.cpu_relax()
    }

    return errno(Errno.EOK)
}
