@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import bridge.runtime_clock_configure_hpet
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hasBit
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

private const val FEMTOSECONDS_PER_NANOSECOND = 1_000_000uL
private const val HPET_COUNTER_SIZE_CAPABILITY_BIT = 13

private fun ticksToNanoseconds(ticks: ULong, femtosecondsPerTick: UInt): ULong {
    val period = femtosecondsPerTick.toULong()
    val wholeTickGroups = ticks / FEMTOSECONDS_PER_NANOSECOND
    if (wholeTickGroups > ULong.MAX_VALUE / period) {
        return ULong.MAX_VALUE
    }

    val wholeNanoseconds = wholeTickGroups * period
    val remainingNanoseconds =
        (ticks % FEMTOSECONDS_PER_NANOSECOND) * period / FEMTOSECONDS_PER_NANOSECOND
    return if (remainingNanoseconds > ULong.MAX_VALUE - wholeNanoseconds) {
        ULong.MAX_VALUE
    } else {
        wholeNanoseconds + remainingNanoseconds
    }
}

object Hpet {
    private const val GAS_SPACE_SYSTEM_MEMORY = 0u
    private const val HPET_MMIO_SIZE = 0x1000uL
    private const val HPET_ROUTE_IRQ_VECTOR = 20u

    private const val GENERAL_CAPABILITIES_OFFSET = 0uL
    private const val COUNTER_PERIOD_OFFSET = 0x4uL
    private const val GENERAL_CONFIGURATION_OFFSET = 0x10uL
    private const val GENERAL_CONFIGURATION_ENABLE = 1uL
    private const val TIMER0_CONFIGURATION_OFFSET = 0x100uL
    private const val MAIN_COUNTER_OFFSET = 0xF0uL

    private var baseVirtualAddress = 0uL
    private var femtosecondsPerTick = 0u

    val isReady: Boolean
        get() = baseVirtualAddress != 0uL && femtosecondsPerTick != 0u

    fun nanoTime(): ULong = if (isReady) {
        ticksToNanoseconds(read64(MAIN_COUNTER_OFFSET), femtosecondsPerTick)
    } else {
        0uL
    }

    fun initialize(baseAddress: ULong, spaceId: UInt) {
        reset()
        if (spaceId != GAS_SPACE_SYSTEM_MEMORY) {
            println("HPET: unsupported GAS space id=$spaceId")
            return
        }

        val mappedBase = KernelPageDirectory.mapMmio(baseAddress, HPET_MMIO_SIZE) ?: run {
            println("HPET: failed to map MMIO at ${baseAddress.hex()}")
            return
        }
        baseVirtualAddress = mappedBase

        val capabilities = read64(GENERAL_CAPABILITIES_OFFSET)
        if (!capabilities.hasBit(HPET_COUNTER_SIZE_CAPABILITY_BIT)) {
            println("HPET: 64-bit main counter unsupported")
            reset()
            return
        }

        val period = read32(COUNTER_PERIOD_OFFSET)
        if (period == 0u) {
            println("HPET: invalid counter period register")
            reset()
            return
        }

        val oldGeneralConfig = read64(GENERAL_CONFIGURATION_OFFSET)
        write64(
            GENERAL_CONFIGURATION_OFFSET,
            oldGeneralConfig and GENERAL_CONFIGURATION_ENABLE.inv(),
        )
        write64(MAIN_COUNTER_OFFSET, 0uL)

        val routeCapabilities = read64(TIMER0_CONFIGURATION_OFFSET) shr 32
        if (!routeCapabilities.hasBit(HPET_ROUTE_IRQ_VECTOR.toInt())) {
            println(
                "HPET: IRQ route vector $HPET_ROUTE_IRQ_VECTOR unsupported, " +
                    "route_cap=${routeCapabilities.hex()}",
            )
        }

        val timerConfig = (HPET_ROUTE_IRQ_VECTOR.toULong() shl 9) or (1uL shl 2)
        write64(TIMER0_CONFIGURATION_OFFSET, timerConfig)
        runtime_clock_configure_hpet(mappedBase.toPointer<UByteVar>(), period.toULong())
        write64(
            GENERAL_CONFIGURATION_OFFSET,
            oldGeneralConfig or GENERAL_CONFIGURATION_ENABLE,
        )

        femtosecondsPerTick = period
        println("HPET: time=${nanoTime()}ns mapped=${mappedBase.hex()} period=${period}fms/tick")
    }

    private fun reset() {
        baseVirtualAddress = 0uL
        femtosecondsPerTick = 0u
    }

    private fun read32(offset: ULong): UInt =
        (baseVirtualAddress + offset).toPointer<UIntVar>()?.get(0) ?: 0u

    private fun read64(offset: ULong): ULong =
        (baseVirtualAddress + offset).toPointer<ULongVar>()?.get(0) ?: 0uL

    private fun write64(offset: ULong, value: ULong) {
        val register = (baseVirtualAddress + offset).toPointer<ULongVar>() ?: return
        register[0] = value
    }
}
