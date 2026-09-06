package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.drivers.char.tty.ConsoleKeyboardMode

internal class ConsoleKeyEncoder {
    private var modifiers = 0
    private var capsLock = false

    fun encode(code: Int, action: KeyAction, mode: ConsoleKeyboardMode, output: ByteArray): Int {
        require(output.size >= MAX_BYTES)
        val key = KeyCode.fromLinuxCode(code)
        val released = action == KeyAction.RELEASED
        key?.modifier?.let {
            modifiers = if (released) modifiers and it.mask.inv() else modifiers or it.mask
        }
        if (key == KeyCode.CAPS_LOCK && action == KeyAction.PRESSED) capsLock = !capsLock
        val up = if (released) 0x80 else 0
        val alt = modifiers and KeyModifier.ALT != 0

        when (mode) {
            ConsoleKeyboardMode.OFF -> return 0
            ConsoleKeyboardMode.MEDIUMRAW -> {
                if (code !in 1..0x3FFF) return 0
                if (code < 128) {
                    output[0] = (code or up).toByte()
                    return 1
                }
                output[0] = up.toByte()
                output[1] = (code shr 7 or 0x80).toByte()
                output[2] = (code or 0x80).toByte()
                return 3
            }
            ConsoleKeyboardMode.RAW -> return when {
                key == KeyCode.PAUSE -> {
                    output[0] = 0xE1.toByte()
                    output[1] = (0x1D or up).toByte()
                    output[2] = (0x45 or up).toByte()
                    3
                }
                key == KeyCode.PRINT_SCREEN -> {
                    if (alt) {
                        output[0] = (0x54 or up).toByte()
                        1
                    } else {
                        output[0] = 0xE0.toByte()
                        output[1] = (0x2A or up).toByte()
                        output[2] = 0xE0.toByte()
                        output[3] = (0x37 or up).toByte()
                        4
                    }
                }
                key == null || key.set1Code < 0 -> 0
                key.extendedSet1 -> {
                    output[0] = 0xE0.toByte()
                    output[1] = (key.set1Code or up).toByte()
                    2
                }
                else -> {
                    output[0] = (key.set1Code or up).toByte()
                    1
                }
            }
            ConsoleKeyboardMode.XLATE, ConsoleKeyboardMode.UNICODE -> Unit
        }

        if (released || key == null || key.modifier != null) return 0
        var count = 0
        if (alt) output[count++] = 0x1B
        key.sequence?.let {
            it.copyInto(output, count)
            return count + it.size
        }
        val shifted = modifiers and KeyModifier.SHIFT != 0
        val uppercase = if (key.letter) shifted.xor(capsLock) else shifted
        val character = (if (uppercase) key.shifted else key.normal) ?: return 0
        var value = character.code
        if (modifiers and KeyModifier.CONTROL != 0) {
            value = when (character) {
                in 'a'..'z' -> value - 'a'.code + 1
                in '@'..'_' -> value and 0x1F
                ' ', '2' -> 0
                '3' -> 0x1B
                '4' -> 0x1C
                '5' -> 0x1D
                '6' -> 0x1E
                '7', '/' -> 0x1F
                '8', '?' -> 0x7F
                else -> value
            }
        }
        if (mode == ConsoleKeyboardMode.XLATE) {
            if (value > 0xFF) return 0
            output[count++] = value.toByte()
        } else {
            when {
                value < 0x80 -> output[count++] = value.toByte()
                value < 0x800 -> {
                    output[count++] = (0xC0 or (value shr 6)).toByte()
                    output[count++] = (0x80 or (value and 0x3F)).toByte()
                }
                else -> {
                    output[count++] = (0xE0 or (value shr 12)).toByte()
                    output[count++] = (0x80 or (value shr 6 and 0x3F)).toByte()
                    output[count++] = (0x80 or (value and 0x3F)).toByte()
                }
            }
        }
        return count
    }

    companion object {
        val MAX_BYTES = maxOf(4, KeyCode.entries.maxOf { (it.sequence?.size ?: 0) + 1 })
    }
}
