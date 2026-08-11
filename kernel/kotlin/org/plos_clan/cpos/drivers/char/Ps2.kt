@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import bridge.io_in8
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.coroutines.KernelEvent
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIoResource
import org.plos_clan.cpos.drivers.acpi.aml.AcpiIrqResource
import org.plos_clan.cpos.drivers.acpi.aml.AmlDeviceInfo
import org.plos_clan.cpos.fault.IRQ_BASE_VECTOR
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.fault.IrqControllerType
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

data class Ps2KeyboardConfiguration(
    val dataPort: UInt,
    val commandPort: UInt,
    val irq: UInt,
    val levelTriggered: Boolean,
    val activeLow: Boolean,
)

object Ps2Keyboard {
    var configuration: Ps2KeyboardConfiguration? = null
        private set

    private val scanCodes = ByteRingBuffer(256)
    private var scanCodeWakeup: KernelEvent? = null

    val isDiscovered: Boolean
        get() = configuration != null

    private var extendedPrefix = false
    private var pauseBytesRemaining = 0
    private var leftShiftPressed = false
    private var rightShiftPressed = false
    private var leftCtrlPressed = false
    private var rightCtrlPressed = false
    private var capsLockEnabled = false

    val shift: Boolean
        get() = leftShiftPressed || rightShiftPressed
    val ctrl: Boolean
        get() = leftCtrlPressed || rightCtrlPressed
    val caps: Boolean
        get() = capsLockEnabled

    fun keyboardHandle(regs: PtraceRegisters, irqNum: ULong) {
        val config = configuration ?: return

        val status = io_in8(config.commandPort.toUShort())
        if ((status.toInt() and 0x01) == 0) {
            return
        }

        val scanCode = io_in8(config.dataPort.toUShort())
        if (scanCodes.offer(scanCode.toByte())) {
            scanCodeWakeup?.signal()
        }
    }

    private fun processScanCode(code: UByte) {
        val rawCode = code.toInt()

        if (pauseBytesRemaining != 0) {
            pauseBytesRemaining--
            return
        }

        when (rawCode) {
            SET1_EXTENDED_PREFIX -> {
                extendedPrefix = true
                return
            }

            SET1_PAUSE_PREFIX -> {
                extendedPrefix = false
                pauseBytesRemaining = SET1_PAUSE_REMAINING_BYTES
                return
            }
        }

        val isExtended = extendedPrefix
        extendedPrefix = false

        val pressed = rawCode and SET1_RELEASE_BIT == 0
        val scanCode = rawCode and SET1_SCAN_CODE_MASK

        if (updateModifier(scanCode, isExtended, pressed) || !pressed) {
            return
        }

        val characters = translatePressedKey(scanCode, isExtended) ?: return
        if (TtyManager.vts.isNotEmpty()) {
            TtyManager.getActiveVT().keyboardInput(characters)
        }
    }

    private fun updateModifier(scanCode: Int, isExtended: Boolean, pressed: Boolean): Boolean {
        when {
            !isExtended && scanCode == SCAN_LEFT_SHIFT -> leftShiftPressed = pressed
            !isExtended && scanCode == SCAN_RIGHT_SHIFT -> rightShiftPressed = pressed
            scanCode == SCAN_CTRL && isExtended -> rightCtrlPressed = pressed
            scanCode == SCAN_CTRL -> leftCtrlPressed = pressed
            !isExtended && scanCode == SCAN_CAPS_LOCK -> {
                if (pressed) {
                    capsLockEnabled = !capsLockEnabled
                }
            }

            else -> return false
        }
        return true
    }

    private fun translatePressedKey(scanCode: Int, isExtended: Boolean): CharArray? {
        if (isExtended) {
            return when (scanCode) {
                SCAN_ENTER -> NEWLINE
                SCAN_SLASH -> ASCII_CHARACTERS[if (shift) '?'.code else '/'.code]
                SCAN_HOME -> CURSOR_HOME
                SCAN_UP -> CURSOR_UP
                SCAN_PAGE_UP -> PAGE_UP
                SCAN_LEFT -> CURSOR_LEFT
                SCAN_RIGHT -> CURSOR_RIGHT
                SCAN_END -> CURSOR_END
                SCAN_DOWN -> CURSOR_DOWN
                SCAN_PAGE_DOWN -> PAGE_DOWN
                SCAN_INSERT -> INSERT
                SCAN_DELETE -> DELETE
                else -> null
            }
        }

        val character = translatePrintableKey(scanCode) ?: return null
        return ASCII_CHARACTERS[toControlCharacter(character).code]
    }

    private fun translatePrintableKey(scanCode: Int): Char? {
        val letter = when (scanCode) {
            in 0x10..0x19 -> "qwertyuiop"[scanCode - 0x10]
            in 0x1E..0x26 -> "asdfghjkl"[scanCode - 0x1E]
            in 0x2C..0x32 -> "zxcvbnm"[scanCode - 0x2C]
            else -> null
        }
        if (letter != null) {
            return if (shift xor caps) letter.uppercaseChar() else letter
        }

        if (scanCode in 0x02..0x0B) {
            val index = scanCode - 0x02
            return if (shift) SHIFTED_DIGITS[index] else DIGITS[index]
        }

        return when (scanCode) {
            SCAN_ESCAPE -> '\u001B'
            SCAN_BACKSPACE -> '\u007F'
            SCAN_TAB -> '\t'
            SCAN_ENTER -> '\n'
            SCAN_SPACE -> ' '
            SCAN_MINUS -> if (shift) '_' else '-'
            SCAN_EQUAL -> if (shift) '+' else '='
            SCAN_LEFT_BRACKET -> if (shift) '{' else '['
            SCAN_RIGHT_BRACKET -> if (shift) '}' else ']'
            SCAN_SEMICOLON -> if (shift) ':' else ';'
            SCAN_APOSTROPHE -> if (shift) '"' else '\''
            SCAN_GRAVE -> if (shift) '~' else '`'
            SCAN_BACKSLASH -> if (shift) '|' else '\\'
            SCAN_COMMA -> if (shift) '<' else ','
            SCAN_DOT -> if (shift) '>' else '.'
            SCAN_SLASH -> if (shift) '?' else '/'
            SCAN_KEYPAD_ASTERISK -> '*'
            SCAN_KEYPAD_MINUS -> '-'
            SCAN_KEYPAD_PLUS -> '+'
            SCAN_KEYPAD_7 -> '7'
            SCAN_KEYPAD_8 -> '8'
            SCAN_KEYPAD_9 -> '9'
            SCAN_KEYPAD_4 -> '4'
            SCAN_KEYPAD_5 -> '5'
            SCAN_KEYPAD_6 -> '6'
            SCAN_KEYPAD_1 -> '1'
            SCAN_KEYPAD_2 -> '2'
            SCAN_KEYPAD_3 -> '3'
            SCAN_KEYPAD_0 -> '0'
            SCAN_KEYPAD_DOT -> '.'
            else -> null
        }
    }

    private fun toControlCharacter(character: Char): Char {
        if (!ctrl) {
            return character
        }

        return when (character) {
            in 'a'..'z' -> (character.code - 'a'.code + 1).toChar()
            in 'A'..'Z' -> (character.code - 'A'.code + 1).toChar()
            else -> character
        }
    }

    fun startKeyboardService() = KernelCoroutines.dispatcher.createEvent().let { wakeup ->
        scanCodeWakeup = wakeup
        KernelCoroutines.scope.launch(CoroutineName("ps2-events")) {
            val batch = ByteArray(32)
            while (isActive) {
                wakeup.await()
                while (true) {
                    val count = scanCodes.read(batch)
                    if (count == 0) {
                        break
                    }
                    repeat(count) { index ->
                        processScanCode(batch[index].toUByte())
                    }
                    yield()
                }
            }
        }
    }

    fun initialize(device: AmlDeviceInfo): Boolean {
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
        val levelTriggered = irqResource?.levelTriggered ?: false
        val activeLow = irqResource?.activeLow ?: false

        configuration = Ps2KeyboardConfiguration(
            dataPort = dataPort,
            commandPort = commandPort,
            irq = irq,
            levelTriggered = levelTriggered,
            activeLow = activeLow,
        )
        val vector = irq + IRQ_BASE_VECTOR
        IrqController.registerAction(
            irq = irq,
            vector = vector,
            masked = false,
            levelTriggered = levelTriggered,
            activeLow = activeLow,
            name = "ps/2-keyboard",
            type = IrqControllerType.IO_APIC,
            handle = ::keyboardHandle,
        )
        startKeyboardService()
        println(
            "PS/2: ACPI keyboard ${device.path} data=0x${dataPort.toString(16)} " +
                    "command=0x${commandPort.toString(16)} irq=$irq " +
                    "levelTriggered=$levelTriggered activeLow=$activeLow",
        )
        return true
    }

    private const val SET1_RELEASE_BIT = 0x80
    private const val SET1_SCAN_CODE_MASK = 0x7F
    private const val SET1_EXTENDED_PREFIX = 0xE0
    private const val SET1_PAUSE_PREFIX = 0xE1
    private const val SET1_PAUSE_REMAINING_BYTES = 5

    private const val SCAN_ESCAPE = 0x01
    private const val SCAN_MINUS = 0x0C
    private const val SCAN_EQUAL = 0x0D
    private const val SCAN_BACKSPACE = 0x0E
    private const val SCAN_TAB = 0x0F
    private const val SCAN_LEFT_BRACKET = 0x1A
    private const val SCAN_RIGHT_BRACKET = 0x1B
    private const val SCAN_ENTER = 0x1C
    private const val SCAN_CTRL = 0x1D
    private const val SCAN_SEMICOLON = 0x27
    private const val SCAN_APOSTROPHE = 0x28
    private const val SCAN_GRAVE = 0x29
    private const val SCAN_LEFT_SHIFT = 0x2A
    private const val SCAN_BACKSLASH = 0x2B
    private const val SCAN_COMMA = 0x33
    private const val SCAN_DOT = 0x34
    private const val SCAN_SLASH = 0x35
    private const val SCAN_RIGHT_SHIFT = 0x36
    private const val SCAN_KEYPAD_ASTERISK = 0x37
    private const val SCAN_SPACE = 0x39
    private const val SCAN_CAPS_LOCK = 0x3A
    private const val SCAN_KEYPAD_7 = 0x47
    private const val SCAN_HOME = SCAN_KEYPAD_7
    private const val SCAN_KEYPAD_8 = 0x48
    private const val SCAN_UP = SCAN_KEYPAD_8
    private const val SCAN_KEYPAD_9 = 0x49
    private const val SCAN_PAGE_UP = SCAN_KEYPAD_9
    private const val SCAN_KEYPAD_MINUS = 0x4A
    private const val SCAN_KEYPAD_4 = 0x4B
    private const val SCAN_LEFT = SCAN_KEYPAD_4
    private const val SCAN_KEYPAD_5 = 0x4C
    private const val SCAN_KEYPAD_6 = 0x4D
    private const val SCAN_RIGHT = SCAN_KEYPAD_6
    private const val SCAN_KEYPAD_PLUS = 0x4E
    private const val SCAN_KEYPAD_1 = 0x4F
    private const val SCAN_END = SCAN_KEYPAD_1
    private const val SCAN_KEYPAD_2 = 0x50
    private const val SCAN_DOWN = SCAN_KEYPAD_2
    private const val SCAN_KEYPAD_3 = 0x51
    private const val SCAN_PAGE_DOWN = SCAN_KEYPAD_3
    private const val SCAN_KEYPAD_0 = 0x52
    private const val SCAN_INSERT = SCAN_KEYPAD_0
    private const val SCAN_KEYPAD_DOT = 0x53
    private const val SCAN_DELETE = SCAN_KEYPAD_DOT

    private const val DIGITS = "1234567890"
    private const val SHIFTED_DIGITS = "!@#$%^&*()"

    private val ASCII_CHARACTERS = Array(128) { code -> charArrayOf(code.toChar()) }
    private val NEWLINE = ASCII_CHARACTERS['\n'.code]
    private val CURSOR_UP = charArrayOf('\u001B', '[', 'A')
    private val CURSOR_DOWN = charArrayOf('\u001B', '[', 'B')
    private val CURSOR_RIGHT = charArrayOf('\u001B', '[', 'C')
    private val CURSOR_LEFT = charArrayOf('\u001B', '[', 'D')
    private val CURSOR_HOME = charArrayOf('\u001B', '[', 'H')
    private val CURSOR_END = charArrayOf('\u001B', '[', 'F')
    private val PAGE_UP = charArrayOf('\u001B', '[', '5', '~')
    private val PAGE_DOWN = charArrayOf('\u001B', '[', '6', '~')
    private val INSERT = charArrayOf('\u001B', '[', '2', '~')
    private val DELETE = charArrayOf('\u001B', '[', '3', '~')
}
