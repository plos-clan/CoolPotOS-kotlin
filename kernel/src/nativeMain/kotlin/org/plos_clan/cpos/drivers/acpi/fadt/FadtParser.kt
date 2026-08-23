@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.fadt

import org.plos_clan.cpos.drivers.acpi.AcpiTable
import org.plos_clan.cpos.drivers.acpi.AcpiTableParser
import org.plos_clan.cpos.utils.checksumOk
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU32
import org.plos_clan.cpos.utils.readU64
import org.plos_clan.cpos.utils.readU8

private const val GAS_LENGTH = 12
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

}
