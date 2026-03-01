@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.apic

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

private const val IOAPIC_MMIO_SIZE = 0x1000uL

private const val IOAPIC_IOREGSEL_OFFSET = 0uL
private const val IOAPIC_IOWIN_OFFSET = 0x10uL

private const val IOAPIC_REG_VERSION = 0x01u
private const val IOAPIC_REG_TABLE_BASE = 0x10u

private const val IOAPIC_POLARITY_LOW_BIT = 0x2000uL
private const val IOAPIC_TRIGGER_LEVEL_BIT = 0x8000uL
private const val IOAPIC_MASK_BIT = 0x1_0000uL

object IoApic {
    private var mmioBaseVirtualAddress = 0uL
    private var redirectionEntryCount = 0u

    fun initialize(physicalAddress: ULong): Boolean {
        val mappedBase = KernelPageDirectory.mapMmio(physicalAddress, IOAPIC_MMIO_SIZE) ?: run {
            println("APIC: failed to map IOAPIC at ${physicalAddress.hex()}")
            return false
        }

        mmioBaseVirtualAddress = mappedBase

        val version = read(IOAPIC_REG_VERSION)
        redirectionEntryCount = ((version shr 16) and 0xFFu) + 1u
        println(
            "APIC: IOAPIC mapped=${mappedBase.hex()} version=${(version and 0xFFu).hex()} entries=$redirectionEntryCount",
        )
        return true
    }

    fun routeIrq(
        irq: UInt,
        vector: UInt,
        destinationApicId: UInt,
        masked: Boolean = false,
        levelTriggered: Boolean = false,
        activeLow: Boolean = false,
    ) {
        if (mmioBaseVirtualAddress == 0uL || redirectionEntryCount == 0u) {
            return
        }
        if (vector > UByte.MAX_VALUE.toUInt()) {
            println("APIC: invalid IOAPIC vector=$vector")
            return
        }
        if (irq >= redirectionEntryCount) {
            println("APIC: irq=$irq exceeds IOAPIC entries=$redirectionEntryCount")
            return
        }

        val tableRegister = IOAPIC_REG_TABLE_BASE + irq * 2u
        var low = vector.toULong()
        if (masked) {
            low = low or IOAPIC_MASK_BIT
        }
        if (levelTriggered) {
            low = low or IOAPIC_TRIGGER_LEVEL_BIT
        }
        if (activeLow) {
            low = low or IOAPIC_POLARITY_LOW_BIT
        }

        val high = (destinationApicId and 0xFFu).toULong() shl 24
        write(tableRegister, low.toUInt())
        write(tableRegister + 1u, high.toUInt())

        println(
            "APIC: route irq=$irq vector=$vector dst_apic_id=${destinationApicId and 0xFFu} masked=$masked",
        )
    }

    private fun read(register: UInt): UInt {
        writeRaw(IOAPIC_IOREGSEL_OFFSET, register)
        return readRaw(IOAPIC_IOWIN_OFFSET)
    }

    private fun write(register: UInt, value: UInt) {
        writeRaw(IOAPIC_IOREGSEL_OFFSET, register)
        writeRaw(IOAPIC_IOWIN_OFFSET, value)
    }

    private fun readRaw(offset: ULong): UInt =
        (mmioBaseVirtualAddress + offset).toPointer<UIntVar>()?.get(0) ?: 0u

    private fun writeRaw(offset: ULong, value: UInt) {
        val pointer = (mmioBaseVirtualAddress + offset).toPointer<UIntVar>() ?: return
        pointer[0] = value
    }
}
