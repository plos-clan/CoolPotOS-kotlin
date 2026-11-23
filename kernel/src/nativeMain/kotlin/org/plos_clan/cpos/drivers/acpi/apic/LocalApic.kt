@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.apic

import bridge.mp_request
import bridge.rdmsr
import bridge.wrmsr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IRQ_LAST_DEVICE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.hex

private const val LIMINE_MP_RESPONSE_X86_64_X2APIC = 1u

private const val LAPIC_MMIO_SIZE = 0x1000uL
private const val X2APIC_MSR_BASE = 0x800u

private const val LAPIC_REG_ID = 0x20u
private const val LAPIC_REG_SPURIOUS = 0xF0u
private const val LAPIC_SPURIOUS_VECTOR = 0xFFu
private const val LAPIC_SPURIOUS_ENABLE_BIT = 0x100uL
private const val LAPIC_REG_TIMER = 0x320u
private const val LAPIC_TIMER_TSC_DEADLINE = 0x40000uL
private const val LAPIC_REG_ISR = 0x100u
private const val LAPIC_REG_EOI = 0xb0u
private const val ISR_STRIDE = 0x10u
private const val DEVICE_SERVICE_BUDGET = 64
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

    fun initialize(physicalAddress: ULong): Boolean {
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
        if (!configureDeadlineTimer()) {
            println("APIC: failed to configure BSP TSC-deadline timer")
            return false
        }
        bspDeadlineTimerReady = true
        return true
    }

    fun configureDeadlineTimer(): Boolean {
        val mode = LAPIC_TIMER_INTERRUPT_VECTOR.toULong() or LAPIC_TIMER_TSC_DEADLINE
        if (!write(LAPIC_REG_TIMER, mode)) return false
        wrmsr(bridge.ia32_tsc_deadline_msr, 0uL)
        return true
    }

    internal fun serviceDevices(): Boolean {
        val last = IRQ_LAST_DEVICE_VECTOR / UInt.SIZE_BITS.toUInt()
        val first = IRQ_BASE_VECTOR / UInt.SIZE_BITS.toUInt()
        val lastMask = UInt.MAX_VALUE shr (31 - (IRQ_LAST_DEVICE_VECTOR % 32u).toInt())
        var remaining = DEVICE_SERVICE_BUDGET
        for (word in last downTo first) {
            val bits = read(LAPIC_REG_ISR + word * ISR_STRIDE).toUInt()
            var pending = if (word == last) bits and lastMask else bits
            while (pending != 0u) {
                val bit = 31 - pending.countLeadingZeroBits()
                pending = pending and (1u shl bit).inv()
                val vector = word * 32u + bit.toUInt()
                IrqController.doIrq((vector - IRQ_BASE_VECTOR + 1u).toULong())
                write(LAPIC_REG_EOI, 0uL)
                if (--remaining == 0) return true
            }
        }
        return false
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
