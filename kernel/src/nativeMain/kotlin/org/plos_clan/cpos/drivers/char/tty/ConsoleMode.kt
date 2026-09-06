package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.utils.VTModeConstants

internal enum class ConsoleDisplayMode(val value: Int) {
    TEXT(VTModeConstants.KD_TEXT),
    GRAPHICS(VTModeConstants.KD_GRAPHICS);

    companion object {
        fun from(value: ULong): ConsoleDisplayMode? = when (value) {
            VTModeConstants.KD_TEXT.toULong(),
            VTModeConstants.KD_TEXT0.toULong(),
            VTModeConstants.KD_TEXT1.toULong() -> TEXT
            VTModeConstants.KD_GRAPHICS.toULong() -> GRAPHICS
            else -> null
        }
    }
}

internal enum class ConsoleKeyboardMode(val value: Int) {
    RAW(VTModeConstants.K_RAW),
    XLATE(VTModeConstants.K_XLATE),
    MEDIUMRAW(VTModeConstants.K_MEDIUMRAW),
    UNICODE(VTModeConstants.K_UNICODE),
    OFF(VTModeConstants.K_OFF);

    companion object {
        fun from(value: UInt): ConsoleKeyboardMode? = entries.firstOrNull { it.value.toUInt() == value }
    }
}
