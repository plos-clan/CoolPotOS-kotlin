@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.fadt

import bridge.asm_pause
import bridge.io_in16
import bridge.io_in32
import bridge.io_in8
import bridge.io_out16
import bridge.io_out32
import bridge.io_out8
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.acpi.aml.Aml
import org.plos_clan.cpos.drivers.acpi.aml.AmlInteger
import org.plos_clan.cpos.drivers.acpi.aml.AmlPackage
import org.plos_clan.cpos.mem.CachedMmioRegion

private const val GAS_ACCESS_UNDEFINED = 0u
private const val GAS_ACCESS_BYTE = 1u
private const val GAS_ACCESS_WORD = 2u
private const val GAS_ACCESS_DWORD = 3u
private const val GAS_ACCESS_QWORD = 4u

internal const val GAS_SYSTEM_MEMORY = 0u
internal const val GAS_SYSTEM_IO = 1u

private const val PM1_SCI_ENABLE = 0x0001uL
private const val PM1_SLEEP_TYPE_SHIFT = 10
private const val PM1_SLEEP_TYPE_MASK = 0x1C00uL
private const val PM1_SLEEP_ENABLE = 0x2000uL
private const val MAX_SLEEP_TYPE = 7u
private const val ACPI_ENABLE_POLL_ATTEMPTS = 1_000_000

private val fadtMemory = CachedMmioRegion()

data class GenericAddressStructure(
    val addressSpaceId: UInt,
    val registerBitWidth: UInt,
    val registerBitOffset: UInt,
    val accessSize: UInt,
    val address: ULong,
) {
    val isPresent: Boolean
        get() = address != 0UL
}

data class FadtInfo(
    val revision: UInt,
    val facsAddress: ULong?,
    val dsdtAddress: ULong?,
    val sciInterrupt: UInt,
    val smiCommandPort: UInt,
    val acpiEnableValue: UInt,
    val acpiDisableValue: UInt,
    val centuryRegister: UInt?,
    val bootArchitectureFlags: UInt?,
    val flags: UInt,
    val resetRegister: GenericAddressStructure?,
    val resetValue: UInt?,
    val pm1aEventBlock: GenericAddressStructure?,
    val pm1bEventBlock: GenericAddressStructure?,
    val pm1aControlBlock: GenericAddressStructure?,
    val pm1bControlBlock: GenericAddressStructure?,
    val pmTimerBlock: GenericAddressStructure?,
    val gpe0Block: GenericAddressStructure?,
    val gpe1Block: GenericAddressStructure?,
    val pm1EventLength: UInt,
    val gpe0Length: UInt,
    val gpe1Length: UInt,
    val gpe1Base: UInt,
) {
    val hasI8042Controller: Boolean?
        get() = bootArchitectureFlags?.let {
            (it and IAPC_BOOT_ARCH_8042) != 0u
        }

    val supportsResetRegister: Boolean
        get() = (flags and FADT_FLAG_RESET_REGISTER_SUPPORTED) != 0u

    val hardwareReducedAcpi: Boolean
        get() = (flags and FADT_FLAG_HARDWARE_REDUCED_ACPI) != 0u

    val hasFixedPowerButton: Boolean
        get() = (flags and FADT_FLAG_POWER_BUTTON_IS_CONTROL_METHOD) == 0u

    companion object {
        const val IAPC_BOOT_ARCH_8042 = 0x00000002u
        const val FADT_FLAG_POWER_BUTTON_IS_CONTROL_METHOD = 0x00000010u
        const val FADT_FLAG_RESET_REGISTER_SUPPORTED = 0x00000400u
        const val FADT_FLAG_HARDWARE_REDUCED_ACPI = 0x00100000u
    }
}

data class S5SleepType(
    val pm1a: UInt,
    val pm1b: UInt,
)

object Fadt {
    private var current: FadtInfo? = null

    val info: FadtInfo?
        get() = current

    val isAvailable: Boolean
        get() = current != null

    internal fun install(info: FadtInfo) {
        current = info
    }

    internal fun ensureAcpiEnabled(): Boolean {
        val fadt = current ?: return false
        if (fadt.hardwareReducedAcpi) {
            return true
        }
        val pm1a = fadt.pm1aControlBlock ?: return false
        return enableAcpi(fadt, pm1a)
    }

    fun shutdown(): Boolean {
        val fadt = current ?: return fail("FADT is unavailable")
        val sleepType = findS5SleepType()
            ?: return fail("cannot evaluate AML _S5 package")
        return shutdown(sleepType.pm1a, sleepType.pm1b)
    }

    fun shutdown(
        sleepTypeA: UInt,
        sleepTypeB: UInt = sleepTypeA,
    ): Boolean {
        val fadt = current ?: return fail("FADT is unavailable")
        if (fadt.hardwareReducedAcpi) {
            return fail("hardware-reduced ACPI S5 is not implemented")
        }
        if (sleepTypeA > MAX_SLEEP_TYPE || sleepTypeB > MAX_SLEEP_TYPE) {
            return fail("invalid S5 sleep type A=$sleepTypeA B=$sleepTypeB")
        }

        val pm1a = fadt.pm1aControlBlock
            ?: return fail("PM1a control block is unavailable")
        if (!enableAcpi(fadt, pm1a)) {
            return false
        }

        val pm1aValue = pm1a.readRaw()
            ?: return fail("cannot read PM1a control block")
        val pm1b = fadt.pm1bControlBlock
        val pm1bValue = pm1b?.readRaw()

        if (pm1b != null && pm1bValue == null) {
            return fail("cannot read PM1b control block")
        }

        val valueA = pm1aValue.withSleepType(sleepTypeA) or PM1_SLEEP_ENABLE
        val valueB = pm1bValue?.withSleepType(sleepTypeB)?.or(PM1_SLEEP_ENABLE)

        if (pm1b != null && valueB != null && !pm1b.writeRaw(valueB)) {
            return fail("cannot write PM1b control block")
        }
        if (!pm1a.writeRaw(valueA)) {
            return fail("cannot write PM1a control block")
        }
        return true
    }

    fun reboot(): Boolean {
        val fadt = current ?: return fail("FADT is unavailable")
        if (!fadt.supportsResetRegister) {
            return fail("firmware does not advertise RESET_REG support")
        }

        val register = fadt.resetRegister
            ?: return fail("RESET_REG is unavailable")
        val value = fadt.resetValue
            ?: return fail("RESET_VALUE is unavailable")

        if (!register.writeRaw(value.toULong())) {
            return fail("cannot write RESET_REG")
        }
        return true
    }

    private fun fail(reason: String): Boolean {
        println("ACPI: $reason")
        return false
    }
}

private fun GenericAddressStructure.accessByteCount(): Int? =
    when (accessSize) {
        GAS_ACCESS_BYTE -> 1
        GAS_ACCESS_WORD -> 2
        GAS_ACCESS_DWORD -> 4
        GAS_ACCESS_QWORD -> 8
        GAS_ACCESS_UNDEFINED -> {
            val requiredBits = registerBitOffset.toULong() + registerBitWidth.toULong()
            when {
                requiredBits in 1uL..8uL -> 1
                requiredBits <= 16uL -> 2
                requiredBits <= 32uL -> 4
                requiredBits <= 64uL -> 8
                else -> null
            }
        }
        else -> null
    }

private fun GenericAddressStructure.readRaw(): ULong? {
    if (!isPresent) {
        return null
    }
    val byteCount = accessByteCount() ?: return null

    return when (addressSpaceId) {
        GAS_SYSTEM_IO -> {
            if (address > 0xFFFFuL || byteCount > UInt.SIZE_BYTES) {
                null
            } else {
                val port = address.toUShort()
                when (byteCount) {
                    1 -> io_in8(port).toULong()
                    2 -> io_in16(port).toULong()
                    4 -> io_in32(port).toULong()
                    else -> null
                }
            }
        }
        GAS_SYSTEM_MEMORY -> readMmio(address, byteCount)
        else -> null
    }
}

private fun GenericAddressStructure.writeRaw(value: ULong): Boolean {
    if (!isPresent) {
        return false
    }
    val byteCount = accessByteCount() ?: return false

    return when (addressSpaceId) {
        GAS_SYSTEM_IO -> {
            if (address > 0xFFFFuL || byteCount > UInt.SIZE_BYTES) {
                false
            } else {
                val port = address.toUShort()
                when (byteCount) {
                    1 -> io_out8(port, value.toUByte())
                    2 -> io_out16(port, value.toUShort())
                    4 -> io_out32(port, value.toUInt())
                    else -> return false
                }
                true
            }
        }
        GAS_SYSTEM_MEMORY -> writeMmio(address, byteCount, value)
        else -> false
    }
}

internal fun GenericAddressStructure.readByte(byteOffset: UInt): UInt? {
    if (byteOffset.toULong() > ULong.MAX_VALUE - address) {
        return null
    }
    return copy(
        registerBitWidth = 8u,
        registerBitOffset = 0u,
        accessSize = GAS_ACCESS_BYTE,
        address = address + byteOffset,
    ).readRaw()?.toUInt()
}

internal fun GenericAddressStructure.writeByte(byteOffset: UInt, value: UInt): Boolean {
    if (byteOffset.toULong() > ULong.MAX_VALUE - address) {
        return false
    }
    return copy(
        registerBitWidth = 8u,
        registerBitOffset = 0u,
        accessSize = GAS_ACCESS_BYTE,
        address = address + byteOffset,
    ).writeRaw(value.toULong())
}

private fun readMmio(
    physicalAddress: ULong,
    byteCount: Int,
): ULong? {
    val address = fadtMemory.addressAt(physicalAddress, byteCount) ?: return null
    return when (byteCount) {
        1 -> address.readU8().toULong()
        2 -> address.readU16().toULong()
        4 -> address.readU32().toULong()
        8 -> address.readU64()
        else -> null
    }
}

private fun writeMmio(
    physicalAddress: ULong,
    byteCount: Int,
    value: ULong,
): Boolean {
    val address = fadtMemory.addressAt(physicalAddress, byteCount) ?: return false
    when (byteCount) {
        1 -> address.writeU8(value.toUByte())
        2 -> address.writeU16(value.toUShort())
        4 -> address.writeU32(value.toUInt())
        8 -> address.writeU64(value)
        else -> return false
    }
    return true
}

private fun enableAcpi(
    fadt: FadtInfo,
    pm1aControlBlock: GenericAddressStructure,
): Boolean {
    if ((pm1aControlBlock.readRaw() ?: return false) and PM1_SCI_ENABLE != 0uL) {
        return true
    }
    if (fadt.smiCommandPort == 0u ||
        fadt.smiCommandPort > 0xFFFFu ||
        fadt.acpiEnableValue == 0u
    ) {
        println("ACPI: firmware did not provide an ACPI enable command")
        return false
    }

    io_out8(fadt.smiCommandPort.toUShort(), fadt.acpiEnableValue.toUByte())
    repeat(ACPI_ENABLE_POLL_ATTEMPTS) {
        val value = pm1aControlBlock.readRaw() ?: return false
        if ((value and PM1_SCI_ENABLE) != 0uL) {
            return true
        }
        asm_pause()
    }

    println("ACPI: timed out while enabling ACPI mode")
    return false
}

private fun ULong.withSleepType(sleepType: UInt): ULong =
    (this and (PM1_SLEEP_TYPE_MASK or PM1_SLEEP_ENABLE).inv()) or
        (sleepType.toULong() shl PM1_SLEEP_TYPE_SHIFT)

private fun findS5SleepType(): S5SleepType? {
    val packageValue = Aml.evaluate("\\_S5_")?.dereference() as? AmlPackage ?: return null
    val sleepTypes = packageValue.elements
        .take(2)
        .map { (Aml.evaluate(it) as? AmlInteger)?.value ?: return null }
    if (sleepTypes.size != 2 || sleepTypes.any { it > MAX_SLEEP_TYPE.toULong() }) return null
    return S5SleepType(sleepTypes[0].toUInt(), sleepTypes[1].toUInt())
}
