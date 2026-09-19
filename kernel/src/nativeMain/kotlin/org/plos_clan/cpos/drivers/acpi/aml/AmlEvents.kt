@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.drivers.acpi.apic.IoApic
import org.plos_clan.cpos.drivers.acpi.fadt.Fadt
import org.plos_clan.cpos.drivers.acpi.fadt.GenericAddressStructure
import org.plos_clan.cpos.drivers.acpi.fadt.readByte
import org.plos_clan.cpos.drivers.acpi.fadt.writeByte
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IRQ_LAST_DEVICE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.utils.IrqSpinLock

private const val PM1_POWER_BUTTON_STATUS = 0x0100u

object AmlEvents {
    private val lock = IrqSpinLock()
    private var sciPending = false
    private var sciInstalled = false
    private var sciGsi = 0u
    private var workerReported = false
    private var workerWakeup: KernelEvent? = null
    private val powerButtons = mutableSetOf<AmlName>()

    fun signalSci(): Boolean {
        lock.withLock { sciPending = true }
        workerWakeup?.signal()
        return true
    }

    internal fun installWorkerWakeup(wakeup: KernelEvent) {
        workerWakeup = wakeup
    }

    internal fun registerPowerButton(path: AmlName, gpe: UInt): Boolean {
        if (!enableGpe(gpe)) {
            println("AML: cannot enable power-button GPE ${gpe.toString(16)} for $path")
            return false
        }
        powerButtons += path
        println("AML: power button $path uses GPE ${gpe.toString(16)}")
        return true
    }

    internal fun notify(path: AmlName, value: ULong) {
        if (value == 0x80uL && path in powerButtons) {
            Fadt.shutdown()
        }
    }

    internal fun installSciRoute(): Boolean {
        if (sciInstalled) {
            return true
        }
        val info = Fadt.info ?: return false
        if (info.hardwareReducedAcpi) {
            println("AML: hardware-reduced SCI handling is not implemented")
            return false
        }
        if (!Fadt.ensureAcpiEnabled()) {
            println("AML: ACPI mode could not be enabled; SCI remains disconnected")
            return false
        }
        if (info.hasFixedPowerButton) {
            configureFixedEvents(info.pm1aEventBlock, info.pm1EventLength)
            configureFixedEvents(info.pm1bEventBlock, info.pm1EventLength)
        }
        val gsi = info.sciInterrupt
        val vector = IRQ_BASE_VECTOR + gsi
        if (vector > IRQ_LAST_DEVICE_VECTOR) {
            println("AML: SCI GSI $gsi exceeds the device interrupt vector range")
            return false
        }

        if (!IrqController.registerIoApic(
                irq = gsi,
                vector = vector,
                masked = true,
                levelTriggered = true,
                activeLow = true,
                name = "aml-sci",
            ) {
                IoApic.setMasked(gsi, true)
                signalSci()
            }
        ) {
            return false
        }
        sciGsi = gsi
        sciInstalled = true
        signalSci()
        println("AML: SCI routed gsi=$gsi vector=$vector")
        return true
    }

    internal fun processPending(maxEvents: Int): Int {
        var processed = 0
        while (processed < maxEvents && takeSciPending()) {
            processSci()
            processed++
        }
        return processed
    }

    private fun processSci() {
        if (!workerReported) {
            workerReported = true
            println("AML: SCI worker ready")
        }
        val info = Fadt.info
        if (info == null) {
            unmaskSci()
            return
        }

        val pm1Status = processPm1Block(
            block = info.pm1aEventBlock,
            byteLength = info.pm1EventLength,
        ) or processPm1Block(
            block = info.pm1bEventBlock,
            byteLength = info.pm1EventLength,
        )

        processGpeBlock(info.gpe0Block, info.gpe0Length, base = 0u)
        processGpeBlock(info.gpe1Block, info.gpe1Length, base = info.gpe1Base)

        unmaskSci()
        if (info.hasFixedPowerButton && (pm1Status and PM1_POWER_BUTTON_STATUS) != 0u) {
            Fadt.shutdown()
        }
    }

    private fun processPm1Block(
        block: GenericAddressStructure?,
        byteLength: UInt,
    ): UInt {
        if (block == null || byteLength < 2u || (byteLength and 1u) != 0u) {
            return 0u
        }
        val registerLength = byteLength / 2u
        var activeEvents = 0u
        var offset = 0u
        while (offset < registerLength && offset < UInt.SIZE_BYTES.toUInt()) {
            val status = block.readByte(offset) ?: break
            val enabled = block.readByte(registerLength + offset) ?: break
            val active = status and enabled
            if (active != 0u) {
                block.writeByte(offset, active)
                activeEvents = activeEvents or (active shl (offset.toInt() * 8))
            }
            offset++
        }
        return activeEvents
    }

    private fun configureFixedEvents(
        block: GenericAddressStructure?,
        byteLength: UInt,
    ) {
        if (block == null || byteLength < 4u || (byteLength and 1u) != 0u) {
            return
        }
        val registerLength = byteLength / 2u
        val powerButtonByte = 1u
        val powerButtonMask = 1u
        block.writeByte(powerButtonByte, powerButtonMask)
        val enabled = block.readByte(registerLength + powerButtonByte) ?: return
        block.writeByte(registerLength + powerButtonByte, enabled or powerButtonMask)
    }

    private fun processGpeBlock(
        block: GenericAddressStructure?,
        byteLength: UInt,
        base: UInt,
    ) {
        if (block == null || byteLength < 2u || (byteLength and 1u) != 0u) {
            return
        }
        val registerLength = byteLength / 2u
        var offset = 0u
        while (offset < registerLength) {
            val status = block.readByte(offset) ?: break
            val enabled = block.readByte(registerLength + offset) ?: break
            val active = status and enabled
            if (active != 0u) {
                block.writeByte(offset, active)
                for (bit in 0 until 8) {
                    if ((active and (1u shl bit)) != 0u) {
                        Aml.evaluateGpe(base + offset * 8u + bit.toUInt())
                    }
                }
            }
            offset++
        }
    }

    private fun enableGpe(number: UInt): Boolean {
        val info = Fadt.info ?: return false
        if (!Fadt.ensureAcpiEnabled()) {
            return false
        }
        val gpe0Count = (info.gpe0Length / 2u) * 8u
        if (number < gpe0Count) {
            return enableGpeInBlock(info.gpe0Block, info.gpe0Length, number)
        }

        val gpe1Count = (info.gpe1Length / 2u) * 8u
        if (number >= info.gpe1Base && number - info.gpe1Base < gpe1Count) {
            return enableGpeInBlock(
                info.gpe1Block,
                info.gpe1Length,
                number - info.gpe1Base,
            )
        }
        return false
    }

    private fun enableGpeInBlock(
        block: GenericAddressStructure?,
        byteLength: UInt,
        relativeNumber: UInt,
    ): Boolean {
        if (block == null || byteLength < 2u || (byteLength and 1u) != 0u) {
            return false
        }
        val registerLength = byteLength / 2u
        val byteOffset = relativeNumber / 8u
        if (byteOffset >= registerLength) {
            return false
        }
        val mask = 1u shl (relativeNumber % 8u).toInt()
        if (!block.writeByte(byteOffset, mask)) {
            return false
        }
        val enabled = block.readByte(registerLength + byteOffset) ?: return false
        return block.writeByte(registerLength + byteOffset, enabled or mask)
    }

    private fun takeSciPending(): Boolean = lock.withLock {
        if (!sciPending) {
            return@withLock false
        }
        sciPending = false
        true
    }

    private fun unmaskSci() {
        if (sciInstalled) {
            IoApic.setMasked(sciGsi, false)
        }
    }
}
