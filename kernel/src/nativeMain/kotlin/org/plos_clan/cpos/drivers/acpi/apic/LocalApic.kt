@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.apic

import bridge.mp_request
import bridge.rdmsr
import bridge.wrmsr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.hex

private const val LIMINE_MP_RESPONSE_X86_64_X2APIC = 1u

private const val LAPIC_MMIO_SIZE = 0x1000uL
private const val X2APIC_MSR_BASE = 0x800u

private const val LAPIC_REG_ID = 0x20u
private const val LAPIC_REG_SPURIOUS = 0xF0u
private const val LAPIC_SPURIOUS_VECTOR = 0xFFu
private const val LAPIC_SPURIOUS_ENABLE_BIT = 0x100uL
private const val LAPIC_MIN_INTERRUPT_VECTOR = 32u
private const val LAPIC_ID_MASK = 0xFFu
private const val LAPIC_ID_MASK_LONG = 0xFFuL

object LocalApic {
    private var x2ApicMode = false
    private var mmioRegion: MmioRegion? = null
    private var bspDeadlineTimerReady = false

    val isX2ApicMode: Boolean
        get() = x2ApicMode

    val isBspDeadlineTimerReady: Boolean
        get() = bspDeadlineTimerReady

    val localApicId: UInt
        get() {
            val rawId = read(LAPIC_REG_ID)
            return if (x2ApicMode) rawId.toUInt() else ((rawId shr 24) and LAPIC_ID_MASK_LONG).toUInt()
        }

    val destinationApicId: UInt
        get() = localApicId and LAPIC_ID_MASK

    fun initialize(
        physicalAddress: ULong,
        timerVector: UInt,
        timerFrequencyHz: UInt,
    ): Boolean {
        bspDeadlineTimerReady = false
        x2ApicMode = detectX2ApicMode()
        if (x2ApicMode) {
            mmioRegion = null
            println("APIC: using x2APIC mode (MSR base=0x800)")
        } else {
            val mappedRegion = MmioRegion.map(physicalAddress, LAPIC_MMIO_SIZE) ?: run {
                println("APIC: failed to map LAPIC at ${physicalAddress.hex()}")
                return false
            }
            mmioRegion = mappedRegion
            println("APIC: using xAPIC mode (base=${mappedRegion.virtualAddress.hex()})")
        }

        bridge.fast_handoff_configure_lapic(
            if (x2ApicMode) 1u.toUByte() else 0u.toUByte(),
            mmioRegion?.virtualAddress ?: 0uL,
        )
        enableController()
        if (timerVector !in LAPIC_MIN_INTERRUPT_VECTOR until LAPIC_SPURIOUS_VECTOR) {
            println("APIC: invalid LAPIC timer vector=$timerVector")
            return false
        }
        if (!configureDeadlineTimer(timerVector.toUByte(), timerFrequencyHz)) {
            println("APIC: failed to configure BSP TSC-deadline timer")
            return false
        }
        bspDeadlineTimerReady = true
        return true
    }

    fun configureDeadlineTimer(
        vector: UByte,
        frequencyHz: UInt,
    ): Boolean {
        if (vector.toUInt() !in LAPIC_MIN_INTERRUPT_VECTOR until LAPIC_SPURIOUS_VECTOR) {
            return false
        }
        return bridge.fast_handoff_configure_timer(vector, frequencyHz)
    }

    fun enableController() {
        write(LAPIC_REG_SPURIOUS, LAPIC_SPURIOUS_VECTOR.toULong() or LAPIC_SPURIOUS_ENABLE_BIT)
    }

    private fun detectX2ApicMode(): Boolean {
        val mpFlags = mp_request.response?.pointed?.flags ?: 0u
        return (mpFlags and LIMINE_MP_RESPONSE_X86_64_X2APIC) != 0u
    }

    private fun read(register: UInt): ULong {
        if (x2ApicMode) {
            return rdmsr(X2APIC_MSR_BASE + (register shr 4))
        }

        return mmioRegion?.addressAt(register.toULong(), UInt.SIZE_BYTES)?.readU32()?.toULong() ?: 0uL
    }

    private fun write(register: UInt, value: ULong): Boolean {
        if (x2ApicMode) {
            wrmsr(X2APIC_MSR_BASE + (register shr 4), value)
            return true
        }

        val address = mmioRegion?.addressAt(register.toULong(), UInt.SIZE_BYTES) ?: return false
        address.writeU32(value.toUInt())
        return true
    }
}
