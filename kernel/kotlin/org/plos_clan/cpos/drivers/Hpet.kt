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

internal fun hpetSupports64BitMainCounter(generalCapabilities: ULong): Boolean =
    generalCapabilities.hasBit(HPET_COUNTER_SIZE_CAPABILITY_BIT)

internal fun hpetTicksToNanoseconds(ticks: ULong, femtosecondsPerTick: UInt): ULong {
    if (femtosecondsPerTick == 0u) {
        return 0uL
    }

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
    private const val TIMER0_CONFIGURATION_OFFSET = 0x100uL
    private const val MAIN_COUNTER_OFFSET = 0xF0uL

    private var baseVirtualAddress = 0uL
    private var fmsPerTick = 0u

    val isReady: Boolean
        get() = baseVirtualAddress != 0uL && fmsPerTick != 0u

    fun nanoTime(): ULong = if (isReady) hpetTicksToNanoseconds(ticks(), fmsPerTick) else 0uL

    fun estimate(ns: ULong): ULong =
        if (isReady) {
            ticks() + (ns * FEMTOSECONDS_PER_NANOSECOND / fmsPerTick.toULong())
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

        initializeMapped(
            mappedBase = mappedBase,
            read32 = { offset -> read32(mappedBase, offset) },
            read64 = { offset -> read64(mappedBase, offset) },
            write64 = { offset, value -> write64(mappedBase, offset, value) },
            configureClock = { base, period ->
                runtime_clock_configure_hpet(base.toPointer<UByteVar>(), period.toULong())
            },
            log = { message -> println(message) },
        )
    }

    internal fun initializeMapped(
        mappedBase: ULong,
        read32: (ULong) -> UInt,
        read64: (ULong) -> ULong,
        write64: (ULong, ULong) -> Unit,
        configureClock: (ULong, UInt) -> Unit,
        log: (String) -> Unit,
    ) {
        reset()

        val generalCapabilities = read64(GENERAL_CAPABILITIES_OFFSET)
        if (!hpetSupports64BitMainCounter(generalCapabilities)) {
            log("HPET: 64-bit main counter unsupported")
            return
        }

        val period = read32(COUNTER_PERIOD_OFFSET)
        if (period == 0u) {
            log("HPET: invalid counter period register")
            return
        }

        write64(MAIN_COUNTER_OFFSET, 0uL)

        val oldGeneralConfig = read64(GENERAL_CONFIGURATION_OFFSET)
        write64(GENERAL_CONFIGURATION_OFFSET, oldGeneralConfig or 1uL)
        configureClock(mappedBase, period)

        val oldTimerConfig = read64(TIMER0_CONFIGURATION_OFFSET)
        val routeCapabilities = oldTimerConfig shr 32
        if (!routeCapabilities.hasBit(HPET_ROUTE_IRQ_VECTOR.toInt())) {
            log("HPET: IRQ route vector $HPET_ROUTE_IRQ_VECTOR unsupported, route_cap=${routeCapabilities.hex()}")
        }

        val timerConfig = (HPET_ROUTE_IRQ_VECTOR.toULong() shl 9) or (1uL shl 2)
        write64(TIMER0_CONFIGURATION_OFFSET, timerConfig)

        baseVirtualAddress = mappedBase
        fmsPerTick = period

        val initializedTime = hpetTicksToNanoseconds(read64(MAIN_COUNTER_OFFSET), period)
        log("HPET: time=${initializedTime}ns mapped=${mappedBase.hex()} period=${period}fms/tick")
    }

    private fun reset() {
        baseVirtualAddress = 0uL
        fmsPerTick = 0u
    }

    private fun ticks(): ULong = if (isReady) read64(baseVirtualAddress, MAIN_COUNTER_OFFSET) else 0uL

    private fun read32(base: ULong, offset: ULong): UInt =
        (base + offset).toPointer<UIntVar>()?.get(0) ?: 0u

    private fun read64(base: ULong, offset: ULong): ULong =
        (base + offset).toPointer<ULongVar>()?.get(0) ?: 0uL

    private fun write64(base: ULong, offset: ULong, value: ULong) {
        val register = (base + offset).toPointer<ULongVar>() ?: return
        register[0] = value
    }
}
