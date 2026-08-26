@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char.terminal

import bridge.TerminalDisplay
import bridge.TerminalPalette
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.utils.IrqSpinLock

private val terminalMalloc = staticCFunction { size: ULong -> bridge.malloc(size) }
private val terminalFree = staticCFunction { pointer: COpaquePointer? -> bridge.free(pointer) }

internal class NativeTerminal private constructor(
    private val handle: COpaquePointer,
    private val invalidate: () -> Unit,
) {
    data class Dimensions(val rows: ULong, val columns: ULong)

    private val lock = IrqSpinLock()
    private var dirty = false

    fun process(data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val invalidated = lock.withLock {
            data.usePinned { bytes ->
                bridge.terminal_process(
                    handle,
                    bytes.addressOf(offset).reinterpret(),
                    length.toULong(),
                )
            }
            val wasClean = !dirty
            dirty = true
            wasClean
        }
        if (invalidated) invalidate()
    }

    fun dimensions(): Dimensions = lock.withLock {
        Dimensions(
            rows = bridge.terminal_rows(handle),
            columns = bridge.terminal_columns(handle),
        )
    }

    fun flushIfDirty() = lock.withLock {
        if (!dirty) return@withLock
        bridge.terminal_flush(handle)
        dirty = false
    }

    fun destroy() = lock.withLock {
        bridge.terminal_destroy(handle)
    }

    companion object {
        private const val FONT_SIZE = 10.0f
        private const val BACKGROUND_COLOR = 0x0d0d1au
        private const val FOREGROUND_COLOR = 0xeaeaeau

        private val ANSI_COLORS = uintArrayOf(
            BACKGROUND_COLOR,
            0xe84a5fu,
            0x50fa7bu,
            0xfacc60u,
            0x61aeeeu,
            0xc074ecu,
            0x40e0d0u,
            0xbebec2u,
            0x2f2f38u,
            0xff6f91u,
            0x8affc1u,
            0xffe99bu,
            0x9ddfffu,
            0xd69fffu,
            0xb2ffffu,
            0xffffffu,
        )

        fun create(
            device: TtyGraphicsDevice,
            invalidate: () -> Unit,
        ): NativeTerminal? = memScoped {
            val display = alloc<TerminalDisplay> {
                width = device.width
                height = device.height
                buffer = device.address?.reinterpret()
                pitch = device.pitch
                red_mask_size = device.redMaskSize
                red_mask_shift = device.redMaskShift
                green_mask_size = device.greenMaskSize
                green_mask_shift = device.greenMaskShift
                blue_mask_size = device.blueMaskSize
                blue_mask_shift = device.blueMaskShift
            }
            val handle = bridge.terminal_new(
                display = display.ptr,
                font_size = FONT_SIZE,
                malloc = terminalMalloc,
                free = terminalFree,
            ) ?: return@memScoped null

            bridge.terminal_set_auto_flush(handle, false)
            val palette = alloc<TerminalPalette> {
                background = BACKGROUND_COLOR
                foreground = FOREGROUND_COLOR
                ANSI_COLORS.forEachIndexed { index, color ->
                    ansi_colors[index] = color
                }
            }
            bridge.terminal_set_custom_color_scheme(handle, palette.ptr)
            bridge.terminal_set_crnl_mapping(handle, false)
            NativeTerminal(handle, invalidate)
        }
    }
}
