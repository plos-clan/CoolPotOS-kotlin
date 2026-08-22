@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.apic

import bridge.io_out8
import kotlinx.cinterop.ExperimentalForeignApi

private const val PIC_MASTER_DATA_PORT: UShort = 0x21u
private const val PIC_SLAVE_DATA_PORT: UShort = 0xA1u
private const val PIC_MASK_ALL: UByte = 0xFFu

const val LAPIC_TIMER_INTERRUPT_VECTOR = 0xFEu
const val LAPIC_TIMER_FREQUENCY_HZ = 1_000u

object Apic {
    fun initialize(
        lapicPhysicalAddress: UInt,
        ioapicPhysicalAddress: UInt,
    ) {
        disableLegacyPic()

        val lapicReady = LocalApic.initialize(
            physicalAddress = lapicPhysicalAddress.toULong(),
            timerVector = LAPIC_TIMER_INTERRUPT_VECTOR,
            timerFrequencyHz = LAPIC_TIMER_FREQUENCY_HZ,
        )
        if (!lapicReady) {
            println("APIC: LAPIC initialization failed")
            return
        }

        if (ioapicPhysicalAddress != 0u) {
            IoApic.initialize(ioapicPhysicalAddress.toULong())
        } else {
            println("APIC: IOAPIC address is unavailable, skip IOAPIC setup")
        }

        val mode = if (LocalApic.isX2ApicMode) "x2APIC" else "xAPIC"
        println("APIC: ready mode=$mode lapic_id=${LocalApic.localApicId}")
    }

    private fun disableLegacyPic() {
        io_out8(PIC_MASTER_DATA_PORT, PIC_MASK_ALL)
        io_out8(PIC_SLAVE_DATA_PORT, PIC_MASK_ALL)
    }
}
