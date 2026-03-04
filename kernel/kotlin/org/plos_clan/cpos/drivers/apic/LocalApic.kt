@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.apic

import bridge.mp_request
import bridge.rdmsr
import bridge.wrmsr
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

private const val LIMINE_MP_RESPONSE_X86_64_X2APIC = 1u

private const val LAPIC_MMIO_SIZE = 0x1000uL
private const val X2APIC_MSR_BASE = 0x800u

private const val LAPIC_REG_ID = 0x20u
private const val LAPIC_REG_EOI = 0xB0u
private const val LAPIC_REG_SPURIOUS = 0xF0u
private const val LAPIC_REG_TIMER = 0x320u
private const val LAPIC_REG_TIMER_INITCNT = 0x380u
private const val LAPIC_REG_TIMER_CURCNT = 0x390u
private const val LAPIC_REG_TIMER_DIV = 0x3E0u

private const val LAPIC_SPURIOUS_VECTOR = 0xFFu
private const val LAPIC_SPURIOUS_ENABLE_BIT = 0x100uL
private const val LAPIC_TIMER_MASK_BIT = 0x1_0000uL
private const val LAPIC_TIMER_PERIODIC_BIT = 0x2_0000uL
private const val LAPIC_TIMER_DIVIDE_BY_1 = 0b1011uL
private const val LAPIC_TIMER_CALIBRATION_NS = 1_000_000uL

object LocalApic {
    private var x2ApicMode = false
    private var mmioBaseVirtualAddress = 0uL
    private var timerInitialCount = 0uL

    val isX2ApicMode: Boolean
        get() = x2ApicMode

    val localApicId: UInt
        get() {
            val rawId = read(LAPIC_REG_ID)
            return if (x2ApicMode) {
                rawId.toUInt()
            } else {
                ((rawId shr 24) and 0xFFuL).toUInt()
            }
        }

    val destinationApicId: UInt
        get() = localApicId and 0xFFu

    fun initialize(
        physicalAddress: ULong,
        timerVector: UInt,
        timerFrequencyHz: UInt,
    ): Boolean {
        x2ApicMode = detectX2ApicMode()
        if (x2ApicMode) {
            mmioBaseVirtualAddress = 0uL
            println("APIC: using x2APIC mode (MSR base=0x800)")
        } else {
            val mappedBase = KernelPageDirectory.mapMmio(physicalAddress, LAPIC_MMIO_SIZE) ?: run {
                println("APIC: failed to map LAPIC at ${physicalAddress.hex()}")
                return false
            }
            mmioBaseVirtualAddress = mappedBase
            println("APIC: using xAPIC mode (base=${mappedBase.hex()})")
        }

        enableController()
        timerInitialCount = calibrateTimer(timerFrequencyHz)
        if (timerInitialCount != 0uL && timerVector <= UByte.MAX_VALUE.toUInt()) {
            configurePeriodicTimer(
                vector = timerVector.toUByte(),
                initialCount = timerInitialCount,
                masked = false,
            )
        }

        return true
    }

    fun endOfInterrupt() {
        write(LAPIC_REG_EOI, 0uL)
    }

    fun configurePeriodicTimer(
        vector: UByte,
        initialCount: ULong = timerInitialCount,
        masked: Boolean = false,
    ) {
        if (initialCount == 0uL) {
            return
        }

        var timerConfig = vector.toULong() or LAPIC_TIMER_PERIODIC_BIT
        if (masked) {
            timerConfig = timerConfig or LAPIC_TIMER_MASK_BIT
        }

        write(LAPIC_REG_TIMER_DIV, LAPIC_TIMER_DIVIDE_BY_1)
        write(LAPIC_REG_TIMER, timerConfig)
        write(LAPIC_REG_TIMER_INITCNT, initialCount)
        println("APIC: LAPIC timer vector=${vector.toUInt()} initial_count=$initialCount masked=$masked")
    }

    private fun enableController() {
        write(LAPIC_REG_SPURIOUS, LAPIC_SPURIOUS_VECTOR.toULong() or LAPIC_SPURIOUS_ENABLE_BIT)
    }

    private fun calibrateTimer(timerFrequencyHz: UInt): ULong {
        if (Hpet.estimate(1uL) == 0uL) {
            println("APIC: HPET unavailable, skip LAPIC timer calibration")
            return 0uL
        }
        if (timerFrequencyHz == 0u) {
            return 0uL
        }

        write(LAPIC_REG_TIMER_DIV, LAPIC_TIMER_DIVIDE_BY_1)
        write(LAPIC_REG_TIMER, LAPIC_TIMER_MASK_BIT)
        write(LAPIC_REG_TIMER_INITCNT, UInt.MAX_VALUE.toULong())

        val start = Hpet.nanoTime()
        while (Hpet.nanoTime() - start < LAPIC_TIMER_CALIBRATION_NS) {
        }

        val elapsedTicks = UInt.MAX_VALUE.toULong() - read(LAPIC_REG_TIMER_CURCNT)
        if (elapsedTicks == 0uL) {
            println("APIC: LAPIC timer calibration produced zero ticks")
            return 0uL
        }

        val calibratedInitialCount = elapsedTicks * 1_000uL / timerFrequencyHz.toULong()
        println("APIC: calibrated LAPIC timer initial_count=$calibratedInitialCount")
        return calibratedInitialCount
    }

    private fun detectX2ApicMode(): Boolean {
        val mpFlags = mp_request.response?.pointed?.flags ?: 0u
        return (mpFlags and LIMINE_MP_RESPONSE_X86_64_X2APIC) != 0u
    }

    private fun read(register: UInt): ULong {
        if (x2ApicMode) {
            return rdmsr(X2APIC_MSR_BASE + (register shr 4))
        }

        val pointer = (mmioBaseVirtualAddress + register.toULong()).toPointer<UIntVar>() ?: return 0uL
        return pointer[0].toULong()
    }

    private fun write(register: UInt, value: ULong) {
        if (x2ApicMode) {
            wrmsr(X2APIC_MSR_BASE + (register shr 4), value)
            return
        }

        val pointer = (mmioBaseVirtualAddress + register.toULong()).toPointer<UIntVar>() ?: return
        pointer[0] = value.toUInt()
    }
}
