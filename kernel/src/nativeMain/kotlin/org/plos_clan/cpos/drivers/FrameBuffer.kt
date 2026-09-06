@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import bridge.framebuffer_request
import bridge.limine_framebuffer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.drivers.char.FrameBufferTerminal
import org.plos_clan.cpos.drivers.char.tty.TtyDriver
import org.plos_clan.cpos.drivers.char.tty.TtyEndpoint
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.utils.VTModeConstants

class TtyGraphicsDevice(
    val address: CPointer<out CPointed>?,
    val width: ULong,
    val height: ULong,
    val pitch: ULong,
    val redMaskSize: UByte,
    val redMaskShift: UByte,
    val greenMaskSize: UByte,
    val greenMaskShift: UByte,
    val blueMaskSize: UByte,
    val blueMaskShift: UByte,
)

private class FrameBufferTtyDriver(
    consoleName: String,
    private val device: TtyGraphicsDevice,
) : TtyDriver(consoleName, terminalType = "linux", bufferedOutput = true) {
    override fun createEndpoints(invalidate: () -> Unit): List<TtyEndpoint> =
        (1..VTModeConstants.MAX_NR_CONSOLES).map { number ->
            TtyEndpoint(
                name = "tty$number",
                major = LinuxDeviceMajor.TTY.number,
                minor = number.toUInt(),
                createBackend = { FrameBufferTerminal.create(device, invalidate) },
                virtualTerminalNumber = number,
            )
        }

}

object FrameBuffer {
    fun initialize() {
        val response = framebuffer_request.response?.pointed
        val framebuffers = response?.framebuffers ?: run {
            println("Framebuffer: no display was provided")
            return
        }

        for (index in 0L until response.framebuffer_count.toLong()) {
            val entry = framebuffers[index]?.pointed ?: continue
            val consoleName = "fb$index"
            if (!TtyManager.install(FrameBufferTtyDriver(consoleName, entry.toGraphicsDevice()))) {
                println("Framebuffer: duplicate console $consoleName")
            }
        }
    }

    private fun limine_framebuffer.toGraphicsDevice() = TtyGraphicsDevice(
        address = address,
        width = width,
        height = height,
        pitch = pitch,
        redMaskSize = red_mask_size,
        redMaskShift = red_mask_shift,
        greenMaskSize = green_mask_size,
        greenMaskShift = green_mask_shift,
        blueMaskSize = blue_mask_size,
        blueMaskShift = blue_mask_shift,
    )
}
