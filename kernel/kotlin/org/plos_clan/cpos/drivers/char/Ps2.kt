@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import bridge.io_in8
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIoResource
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIrqResource
import org.plos_clan.cpos.drivers.acpi.aml.AmlDeviceInfo
import org.plos_clan.cpos.drivers.input.InputId
import org.plos_clan.cpos.drivers.input.InputManager
import org.plos_clan.cpos.drivers.input.KeyCode
import org.plos_clan.cpos.drivers.input.KeyboardInputDevice
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.fault.IrqControllerType
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

private data class Ps2KeyboardConfiguration(
    val dataPort: UInt,
    val commandPort: UInt,
    val irq: UInt,
    val levelTriggered: Boolean,
    val activeLow: Boolean,
)

private class Ps2Set1Decoder(
    private val keyboard: KeyboardInputDevice,
) {
    private var extended = false
    private var pauseBytesRemaining = 0

    fun accept(value: UByte) {
        val raw = value.toInt()
        if (pauseBytesRemaining != 0) {
            pauseBytesRemaining--
            if (pauseBytesRemaining == 0) keyboard.submitPause()
            return
        }
        when (raw) {
            SET1_EXTENDED_PREFIX -> {
                extended = true
                return
            }
            SET1_PAUSE_PREFIX -> {
                extended = false
                pauseBytesRemaining = SET1_PAUSE_REMAINING_BYTES
                return
            }
        }

        val currentExtended = extended
        extended = false
        val code = raw and SET1_SCAN_CODE_MASK
        val key = KeyCode.fromSet1(code, currentExtended) ?: return
        keyboard.submit(key, raw and SET1_RELEASE_BIT == 0)
    }

    private companion object {
        const val SET1_RELEASE_BIT = 0x80
        const val SET1_SCAN_CODE_MASK = 0x7F
        const val SET1_EXTENDED_PREFIX = 0xE0
        const val SET1_PAUSE_PREFIX = 0xE1
        const val SET1_PAUSE_REMAINING_BYTES = 5
    }
}

object Ps2Keyboard {
    private const val STATUS_OUTPUT_FULL = 1
    private const val SCAN_CODE_BATCH_SIZE = 32
    private const val PHYSICAL_PATH = "isa0060/serio0"

    private val scanCodes = ByteRingBuffer(256)
    private var scanCodeWakeup: KernelEvent? = null

    private var configuration: Ps2KeyboardConfiguration? = null

    fun keyboardHandle(
        @Suppress("UNUSED_PARAMETER") registers: PtraceRegisters,
        @Suppress("UNUSED_PARAMETER") irqNumber: ULong,
    ) {
        val config = configuration ?: return
        if (io_in8(config.commandPort.toUShort()).toInt() and STATUS_OUTPUT_FULL == 0) return
        if (scanCodes.offer(io_in8(config.dataPort.toUShort()).toByte())) {
            scanCodeWakeup?.signal()
        }
    }

    fun initialize(device: AmlDeviceInfo): Boolean {
        if (configuration != null) return true
        val ioResources = device.resources.filterIsInstance<AcpiIoResource>()
        val dataPort = ioResources.firstNotNullOfOrNull { resource ->
            0x60u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x60u
        val commandPort = ioResources.firstNotNullOfOrNull { resource ->
            0x64u.takeIf { it in resource.minimum..resource.maximum }
        } ?: 0x64u
        val irqResource = device.resources
            .filterIsInstance<AcpiIrqResource>()
            .firstOrNull { it.interrupts.isNotEmpty() }
        val irq = irqResource?.interrupts?.firstOrNull() ?: 1u
        val config = Ps2KeyboardConfiguration(
            dataPort,
            commandPort,
            irq,
            irqResource?.levelTriggered ?: false,
            irqResource?.activeLow ?: false,
        )
        val keyboard = InputManager.registerKeyboard(
            source = this,
            name = "AT Translated Set 2 keyboard",
            physicalPath = PHYSICAL_PATH,
            id = InputId(InputId.BUS_I8042, product = 1u, version = 1u),
        ) ?: return false

        configuration = config
        startEventWorker(Ps2Set1Decoder(keyboard))
        val vector = irq + IRQ_BASE_VECTOR
        IrqController.registerAction(
            irq = irq,
            vector = vector,
            masked = false,
            levelTriggered = config.levelTriggered,
            activeLow = config.activeLow,
            name = "ps/2-keyboard",
            type = IrqControllerType.IO_APIC,
            handle = ::keyboardHandle,
        )
        println(
            "PS/2: keyboard ${device.path} data=0x${dataPort.toString(16)} " +
                "command=0x${commandPort.toString(16)} irq=$irq " +
                "levelTriggered=${config.levelTriggered} activeLow=${config.activeLow}",
        )
        return true
    }

    private fun startEventWorker(decoder: Ps2Set1Decoder) {
        val wakeup = KernelCoroutines.dispatcher.createEvent()
        scanCodeWakeup = wakeup
        KernelCoroutines.launch("ps2-keyboard") {
            val batch = ByteArray(SCAN_CODE_BATCH_SIZE)
            while (isActive) {
                wakeup.await()
                var count = scanCodes.read(batch)
                while (count != 0) {
                    repeat(count) { decoder.accept(batch[it].toUByte()) }
                    yield()
                    count = scanCodes.read(batch)
                }
            }
        }
    }
}
