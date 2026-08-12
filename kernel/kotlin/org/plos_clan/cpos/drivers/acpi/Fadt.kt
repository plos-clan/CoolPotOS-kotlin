@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi

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
import org.plos_clan.cpos.drivers.acpi.aml.dereference
import org.plos_clan.cpos.mem.CachedMmioRegion
import org.plos_clan.cpos.utils.checksumOk
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU32
import org.plos_clan.cpos.utils.readU64
import org.plos_clan.cpos.utils.readU8

private const val GAS_LENGTH = 12

private const val GAS_SYSTEM_MEMORY = 0u
private const val GAS_SYSTEM_IO = 1u
private const val GAS_PCI_CONFIGURATION = 2u
private const val GAS_EMBEDDED_CONTROLLER = 3u
private const val GAS_SMBUS = 4u
private const val GAS_FIXED_HARDWARE = 0x7Fu

private const val FADT_MIN_LENGTH = 116

private const val FADT_FACS_OFFSET = 36
private const val FADT_DSDT_OFFSET = 40
private const val FADT_SCI_INTERRUPT_OFFSET = 46
private const val FADT_SMI_COMMAND_OFFSET = 48
private const val FADT_ACPI_ENABLE_OFFSET = 52
private const val FADT_ACPI_DISABLE_OFFSET = 53

private const val FADT_PM1A_EVENT_OFFSET = 56
private const val FADT_PM1B_EVENT_OFFSET = 60
private const val FADT_PM1A_CONTROL_OFFSET = 64
private const val FADT_PM1B_CONTROL_OFFSET = 68
private const val FADT_PM_TIMER_OFFSET = 76
private const val FADT_GPE0_OFFSET = 80
private const val FADT_GPE1_OFFSET = 84
private const val FADT_PM1_EVENT_LENGTH_OFFSET = 88
private const val FADT_PM1_CONTROL_LENGTH_OFFSET = 89
private const val FADT_PM_TIMER_LENGTH_OFFSET = 91
private const val FADT_GPE0_LENGTH_OFFSET = 92
private const val FADT_GPE1_LENGTH_OFFSET = 93
private const val FADT_GPE1_BASE_OFFSET = 94

private const val FADT_CENTURY_OFFSET = 108
private const val FADT_IAPC_BOOT_ARCH_OFFSET = 109
private const val FADT_FLAGS_OFFSET = 112

private const val FADT_RESET_REGISTER_OFFSET = 116
private const val FADT_RESET_VALUE_OFFSET = 128
private const val FADT_X_FACS_OFFSET = 132
private const val FADT_X_DSDT_OFFSET = 140
private const val FADT_X_PM1A_EVENT_OFFSET = 148
private const val FADT_X_PM1B_EVENT_OFFSET = 160
private const val FADT_X_PM1A_CONTROL_OFFSET = 172
private const val FADT_X_PM1B_CONTROL_OFFSET = 184
private const val FADT_X_PM_TIMER_OFFSET = 208
private const val FADT_X_GPE0_OFFSET = 220
private const val FADT_X_GPE1_OFFSET = 232

private const val GAS_ACCESS_UNDEFINED = 0u
private const val GAS_ACCESS_BYTE = 1u
private const val GAS_ACCESS_WORD = 2u
private const val GAS_ACCESS_DWORD = 3u
private const val GAS_ACCESS_QWORD = 4u

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

private fun AcpiTable.hasRange(offset: Int, size: Int): Boolean {
    if (offset < 0 || size < 0 || size > length) {
        return false
    }
    return offset <= length - size
}

private fun AcpiTable.readGas(offset: Int): GenericAddressStructure? {
    if (!hasRange(offset, GAS_LENGTH)) {
        return null
    }

    return GenericAddressStructure(
        addressSpaceId = pointer.readU8(offset).toUInt(),
        registerBitWidth = pointer.readU8(offset + 1).toUInt(),
        registerBitOffset = pointer.readU8(offset + 2).toUInt(),
        accessSize = pointer.readU8(offset + 3).toUInt(),
        address = pointer.readU64(offset + 4),
    )
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

private fun selectAddress(
    legacy: UInt,
    extended: ULong?,
): ULong? {
    if (extended != null && extended != 0UL) {
        return extended
    }

    return legacy
        .toULong()
        .takeIf { it != 0UL }
}

private fun legacyIoGas(
    address: UInt,
    byteLength: UInt,
): GenericAddressStructure? {
    if (address == 0u || byteLength == 0u) {
        return null
    }

    return GenericAddressStructure(
        addressSpaceId = GAS_SYSTEM_IO,
        registerBitWidth = byteLength * 8u,
        registerBitOffset = 0u,
        accessSize = 0u,
        address = address.toULong(),
    )
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

object FadtParser : AcpiTableParser<FadtInfo> {
    override val signature: String = "FACP"

    override fun parse(table: AcpiTable): FadtInfo? {
        if (!table.hasLength(FADT_MIN_LENGTH)) {
            println("ACPI: FADT is too short: ${table.length}")
            return null
        }

        if (!table.pointer.checksumOk(table.length)) {
            println("ACPI: FADT checksum failed")
            return null
        }

        val legacyFacs = table.pointer.readU32(FADT_FACS_OFFSET)
        val legacyDsdt = table.pointer.readU32(FADT_DSDT_OFFSET)

        val extendedFacs =
            if (table.hasRange(FADT_X_FACS_OFFSET, 8)) {
                table.pointer.readU64(FADT_X_FACS_OFFSET)
            } else {
                null
            }

        val extendedDsdt =
            if (table.hasRange(FADT_X_DSDT_OFFSET, 8)) {
                table.pointer.readU64(FADT_X_DSDT_OFFSET)
            } else {
                null
            }

        val pm1ControlLength =
            table.pointer.readU8(FADT_PM1_CONTROL_LENGTH_OFFSET).toUInt()

        val pm1EventLength =
            table.pointer.readU8(FADT_PM1_EVENT_LENGTH_OFFSET).toUInt()

        val pmTimerLength =
            table.pointer.readU8(FADT_PM_TIMER_LENGTH_OFFSET).toUInt()

        val gpe0Length =
            table.pointer.readU8(FADT_GPE0_LENGTH_OFFSET).toUInt()

        val gpe1Length =
            table.pointer.readU8(FADT_GPE1_LENGTH_OFFSET).toUInt()

        val legacyPm1aEvent = legacyIoGas(
            address = table.pointer.readU32(FADT_PM1A_EVENT_OFFSET),
            byteLength = pm1EventLength,
        )

        val legacyPm1bEvent = legacyIoGas(
            address = table.pointer.readU32(FADT_PM1B_EVENT_OFFSET),
            byteLength = pm1EventLength,
        )

        val legacyPm1aControl = legacyIoGas(
            address = table.pointer.readU32(FADT_PM1A_CONTROL_OFFSET),
            byteLength = pm1ControlLength,
        )

        val legacyPm1bControl = legacyIoGas(
            address = table.pointer.readU32(FADT_PM1B_CONTROL_OFFSET),
            byteLength = pm1ControlLength,
        )

        val legacyPmTimer = legacyIoGas(
            address = table.pointer.readU32(FADT_PM_TIMER_OFFSET),
            byteLength = pmTimerLength,
        )

        val legacyGpe0 = legacyIoGas(
            address = table.pointer.readU32(FADT_GPE0_OFFSET),
            byteLength = gpe0Length,
        )

        val legacyGpe1 = legacyIoGas(
            address = table.pointer.readU32(FADT_GPE1_OFFSET),
            byteLength = gpe1Length,
        )

        val extendedPm1aEvent =
            table.readGas(FADT_X_PM1A_EVENT_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedPm1bEvent =
            table.readGas(FADT_X_PM1B_EVENT_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedPm1aControl =
            table.readGas(FADT_X_PM1A_CONTROL_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedPm1bControl =
            table.readGas(FADT_X_PM1B_CONTROL_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedPmTimer =
            table.readGas(FADT_X_PM_TIMER_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedGpe0 =
            table.readGas(FADT_X_GPE0_OFFSET)
                ?.takeIf { it.isPresent }

        val extendedGpe1 =
            table.readGas(FADT_X_GPE1_OFFSET)
                ?.takeIf { it.isPresent }

        return FadtInfo(
            revision = table.pointer.readU8(8).toUInt(),
            facsAddress = selectAddress(legacyFacs, extendedFacs),
            dsdtAddress = selectAddress(legacyDsdt, extendedDsdt),
            sciInterrupt =
                table.pointer.readU16(FADT_SCI_INTERRUPT_OFFSET).toUInt(),
            smiCommandPort =
                table.pointer.readU32(FADT_SMI_COMMAND_OFFSET),
            acpiEnableValue =
                table.pointer.readU8(FADT_ACPI_ENABLE_OFFSET).toUInt(),
            acpiDisableValue =
                table.pointer.readU8(FADT_ACPI_DISABLE_OFFSET).toUInt(),
            centuryRegister =
                table.pointer.readU8(FADT_CENTURY_OFFSET)
                    .toUInt()
                    .takeIf { it != 0u },
            bootArchitectureFlags =
                if (table.hasRange(FADT_IAPC_BOOT_ARCH_OFFSET, 2)) {
                    table.pointer
                        .readU16(FADT_IAPC_BOOT_ARCH_OFFSET)
                        .toUInt()
                } else {
                    null
                },
            flags = table.pointer.readU32(FADT_FLAGS_OFFSET),
            resetRegister =
                table.readGas(FADT_RESET_REGISTER_OFFSET)
                    ?.takeIf { it.isPresent },
            resetValue =
                if (table.hasRange(FADT_RESET_VALUE_OFFSET, 1)) {
                    table.pointer
                        .readU8(FADT_RESET_VALUE_OFFSET)
                        .toUInt()
                } else {
                    null
                },
            pm1aEventBlock = extendedPm1aEvent ?: legacyPm1aEvent,
            pm1bEventBlock = extendedPm1bEvent ?: legacyPm1bEvent,
            pm1aControlBlock = extendedPm1aControl ?: legacyPm1aControl,
            pm1bControlBlock = extendedPm1bControl ?: legacyPm1bControl,
            pmTimerBlock = extendedPmTimer ?: legacyPmTimer,
            gpe0Block = extendedGpe0 ?: legacyGpe0,
            gpe1Block = extendedGpe1 ?: legacyGpe1,
            pm1EventLength = pm1EventLength,
            gpe0Length = gpe0Length,
            gpe1Length = gpe1Length,
            gpe1Base = table.pointer.readU8(FADT_GPE1_BASE_OFFSET).toUInt(),
        ).also(Fadt::install)
    }
}
