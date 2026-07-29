@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import bridge.TerminalDisplay
import bridge.TerminalPalette
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock

private fun allocateTerminalMemory(size: ULong): COpaquePointer? = bridge.malloc(size)

private fun releaseTerminalMemory(pointer: COpaquePointer?) {
    bridge.free(pointer)
}

private val terminalMallocCallback = staticCFunction(::allocateTerminalMemory)
private val terminalFreeCallback = staticCFunction(::releaseTerminalMemory)

private val terminalAnsiColors = uintArrayOf(
    0x0d0d1au,
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

class TerminalSession(device: TtyGraphicsDevice) : TtySessionBackend {

    private val terminal: COpaquePointer? = memScoped {
        val display = alloc<TerminalDisplay> {
            width = device.width
            height = device.height
            buffer = device.address?.reinterpret()
            pitch = device.pitch
            red_mask_size = device.red_mask_size
            red_mask_shift = device.red_mask_shift
            green_mask_size = device.green_mask_size
            green_mask_shift = device.green_mask_shift
            blue_mask_size = device.blue_mask_size
            blue_mask_shift = device.blue_mask_shift
        }

        bridge.terminal_new(
            display = display.ptr,
            font_size_bits = 10.0f.toRawBits().toUInt(),
            malloc = terminalMallocCallback,
            free = terminalFreeCallback,
        )
    }.also { terminal ->
        if (terminal == null) {
            return@also
        }
        memScoped {
            val palette = alloc<TerminalPalette> {
                background = 0x0d0d1au
                foreground = 0xeaeaeau
                terminalAnsiColors.forEachIndexed { index, color ->
                    ansi_colors[index] = color
                }
            }
            bridge.terminal_set_custom_color_scheme(terminal, palette.ptr)
        }
    }

    private val lock = IrqSpinLock()

    override fun write(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        val length = minOf(count, buffer.size.toULong()).toInt()
        lock.withLock {
            bridge.terminal_process(terminal, buffer.decodeToString(0, length))
        }
        return count
    }

    override fun read(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        return 0u
    }

    override fun flush(session: TtySession) {
        lock.withLock {
            bridge.terminal_flush(terminal)
        }
    }

    override fun ioctl(
        session: TtySession,
        command: Int,
        args: UserMemory
    ): Int {
        TODO("Not yet implemented")
    }

    override fun poll(
        session: TtySession,
        events: Int
    ): Int {
        TODO("Not yet implemented")
    }

}
