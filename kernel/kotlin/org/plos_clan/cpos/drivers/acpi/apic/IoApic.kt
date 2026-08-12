package org.plos_clan.cpos.drivers.acpi.apic

import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.hex

private const val IOAPIC_MMIO_SIZE = 0x1000uL

private const val IOAPIC_IOREGSEL_OFFSET = 0uL
private const val IOAPIC_IOWIN_OFFSET = 0x10uL

private const val IOAPIC_REG_VERSION = 0x01u
private const val IOAPIC_REG_TABLE_BASE = 0x10u

private const val IOAPIC_BYTE_MASK = 0xFFu
private const val IOAPIC_DESTINATION_SHIFT = 24
private const val IOAPIC_POLARITY_LOW_BIT = 0x2000uL
private const val IOAPIC_TRIGGER_LEVEL_BIT = 0x8000uL
private const val IOAPIC_MASK_BIT = 0x1_0000uL

object IoApic {
    private val lock = IrqSpinLock()
    private var mmioRegion: MmioRegion? = null
    private var redirectionEntryCount = 0u

    fun initialize(physicalAddress: ULong): Boolean {
        val mappedRegion = MmioRegion.map(physicalAddress, IOAPIC_MMIO_SIZE) ?: run {
            println("APIC: failed to map IOAPIC at ${physicalAddress.hex()}")
            return false
        }

        mmioRegion = mappedRegion

        val version = lock.withLock { readUnlocked(IOAPIC_REG_VERSION) }
        redirectionEntryCount = ((version shr 16) and IOAPIC_BYTE_MASK) + 1u
        val versionId = (version and IOAPIC_BYTE_MASK).hex()
        println(
            "APIC: IOAPIC mapped=${mappedRegion.virtualAddress.hex()} " +
                "version=$versionId entries=$redirectionEntryCount",
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
        if (mmioRegion == null || redirectionEntryCount == 0u) {
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
        val low = vector.toULong() or
            (if (masked) IOAPIC_MASK_BIT else 0uL) or
            (if (levelTriggered) IOAPIC_TRIGGER_LEVEL_BIT else 0uL) or
            (if (activeLow) IOAPIC_POLARITY_LOW_BIT else 0uL)

        val destination = destinationApicId and IOAPIC_BYTE_MASK
        val high = destination.toULong() shl IOAPIC_DESTINATION_SHIFT
        lock.withLock {
            writeUnlocked(tableRegister, low.toUInt())
            writeUnlocked(tableRegister + 1u, high.toUInt())
        }

        println("APIC: route irq=$irq vector=$vector dst_apic_id=$destination masked=$masked")
    }

    fun setMasked(irq: UInt, masked: Boolean): Boolean {
        if (mmioRegion == null || irq >= redirectionEntryCount) {
            return false
        }
        return lock.withLock {
            val tableRegister = IOAPIC_REG_TABLE_BASE + irq * 2u
            val current = readUnlocked(tableRegister)
            val updated = if (masked) {
                current or IOAPIC_MASK_BIT.toUInt()
            } else {
                current and IOAPIC_MASK_BIT.toUInt().inv()
            }
            writeUnlocked(tableRegister, updated)
            true
        }
    }

    private fun readUnlocked(register: UInt): UInt {
        writeRaw(IOAPIC_IOREGSEL_OFFSET, register)
        return readRaw(IOAPIC_IOWIN_OFFSET)
    }

    private fun writeUnlocked(register: UInt, value: UInt) {
        writeRaw(IOAPIC_IOREGSEL_OFFSET, register)
        writeRaw(IOAPIC_IOWIN_OFFSET, value)
    }

    private fun readRaw(offset: ULong): UInt =
        mmioRegion?.addressAt(offset, UInt.SIZE_BYTES)?.readU32() ?: 0u

    private fun writeRaw(offset: ULong, value: UInt) {
        mmioRegion?.addressAt(offset, UInt.SIZE_BYTES)?.writeU32(value)
    }
}
