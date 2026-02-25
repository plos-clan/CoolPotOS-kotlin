@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.driver

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hasBit
import org.plos_clan.cpos.utils.hex64
import org.plos_clan.cpos.utils.toPointer

object Hpet {
    private const val GAS_SPACE_SYSTEM_MEMORY = 0u
    private const val HPET_MMIO_SIZE = 0x1000uL

    private const val FEMTOSECONDS_PER_NANOSECOND = 1_000_000uL
    private const val HPET_ROUTE_IRQ_VECTOR = 20u

    private const val COUNTER_PERIOD_OFFSET = 0x4uL
    private const val GENERAL_CONFIGURATION_OFFSET = 0x10uL
    private const val TIMER0_CONFIGURATION_OFFSET = 0x100uL
    private const val TIMER0_COMPARATOR_OFFSET = 0x108uL
    private const val MAIN_COUNTER_OFFSET = 0xF0uL

    private var baseVirtualAddress: ULong = 0uL
    private var fmsPerTick: ULong = 0uL
    private var initialized = false

    val isReady: Boolean
        get() = initialized && baseVirtualAddress != 0uL && fmsPerTick != 0uL

    fun ticks(): ULong {
        if (!isReady) {
            return 0uL
        }
        return read64(MAIN_COUNTER_OFFSET)
    }

    fun nanoTime(): ULong {
        if (!isReady) {
            return 0uL
        }
        return ticks() * fmsPerTick / FEMTOSECONDS_PER_NANOSECOND
    }

    fun estimate(ns: ULong): ULong {
        if (!isReady) {
            return 0uL
        }
        return ticks() + (ns * FEMTOSECONDS_PER_NANOSECOND / fmsPerTick)
    }

    fun busyWait(ns: ULong) {
        if (!isReady || ns == 0uL) {
            return
        }
        val deadline = estimate(ns)
        while (ticks() < deadline) {
        }
    }

    fun setTimer(value: ULong) {
        if (!isReady) {
            return
        }
        write64(TIMER0_COMPARATOR_OFFSET, value)
    }

    fun initialize(baseAddress: ULong, spaceId: UInt) {
        reset()

        if (spaceId != GAS_SPACE_SYSTEM_MEMORY) {
            println("HPET: unsupported GAS space id=$spaceId")
            return
        }

        val mappedBase = KernelPageDirectory.mapMmio(baseAddress, HPET_MMIO_SIZE)
        if (mappedBase == null) {
            println("HPET: failed to map MMIO at 0x${baseAddress.hex64()}")
            return
        }

        baseVirtualAddress = mappedBase
        fmsPerTick = read32(COUNTER_PERIOD_OFFSET).toULong()
        if (fmsPerTick == 0uL) {
            println("HPET: invalid counter period register")
            reset()
            return
        }

        write64(MAIN_COUNTER_OFFSET, 0uL)

        val oldGeneralConfig = read64(GENERAL_CONFIGURATION_OFFSET)
        write64(GENERAL_CONFIGURATION_OFFSET, oldGeneralConfig or 1uL)

        val oldTimerConfig = read64(TIMER0_CONFIGURATION_OFFSET)
        val routeCapabilities = oldTimerConfig shr 32
        if (!routeCapabilities.hasBit(HPET_ROUTE_IRQ_VECTOR.toInt())) {
            println(
                "HPET: IRQ route vector $HPET_ROUTE_IRQ_VECTOR unsupported, route_cap=0x${routeCapabilities.hex64()}",
            )
        }

        val timerConfig = (HPET_ROUTE_IRQ_VECTOR.toULong() shl 9) or (1uL shl 2)
        write64(TIMER0_CONFIGURATION_OFFSET, timerConfig)

        initialized = true
        println(
            "HPET: time=${nanoTime()}ns mapped=0x${mappedBase.hex64()} period=${fmsPerTick}fms/tick",
        )
    }

    private fun reset() {
        baseVirtualAddress = 0uL
        fmsPerTick = 0uL
        initialized = false
    }

    private fun read32(offset: ULong): UInt {
        val register = (baseVirtualAddress + offset).toPointer<UIntVar>() ?: return 0u
        return register[0]
    }

    private fun read64(offset: ULong): ULong {
        val register = (baseVirtualAddress + offset).toPointer<ULongVar>() ?: return 0uL
        return register[0]
    }

    private fun write64(offset: ULong, value: ULong) {
        val register = (baseVirtualAddress + offset).toPointer<ULongVar>() ?: return
        register[0] = value
    }
}
