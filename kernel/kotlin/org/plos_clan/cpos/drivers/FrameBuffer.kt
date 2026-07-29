@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import bridge.framebuffer_request
import bridge.limine_framebuffer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.drivers.char.TtyDevice
import org.plos_clan.cpos.drivers.char.TtyDeviceType
import org.plos_clan.cpos.drivers.char.TtyManager
import org.plos_clan.cpos.drivers.char.TtyPhysicalDevice
import org.plos_clan.cpos.drivers.char.TtySession
import org.plos_clan.cpos.mem.UserMemory

class TtyGraphicsDevice(
    val address: CPointer<out CPointed>?,
    val width: ULong,
    val height: ULong,
    val pitch: ULong,
    val bpp: UShort,
    val memory_model: UByte,
    val red_mask_size: UByte,
    val red_mask_shift: UByte,
    val green_mask_size: UByte,
    val green_mask_shift: UByte,
    val blue_mask_size: UByte,
    val blue_mask_shift: UByte
) : TtyPhysicalDevice {
    override fun write(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        return 0u
    }

    override fun read(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        return 0u
    }

    override fun flush(session: TtySession) {}

    override fun ioctl(
        session: TtySession,
        command: Int,
        args: UserMemory
    ): Int {
        return 0
    }

}

object FrameBuffer {

    private fun installTtyDevice(index: Long,entry: limine_framebuffer) {
        val physicalDevice = TtyGraphicsDevice(
            entry.address,
            entry.width,
            entry.height,
            entry.pitch,
            entry.bpp,
            entry.memory_model,
            entry.red_mask_size,
            entry.red_mask_shift,
            entry.green_mask_size,
            entry.green_mask_shift,
            entry.blue_mask_size,
            entry.blue_mask_shift
        )
        TtyManager.installTtyDevice(
            TtyDevice(
                "fb$index",
                physicalDevice,
                TtyDeviceType.TTY_GRAPHY_DEVICE
            )
        )
    }

    fun initialize() {
        val framebuffer = framebuffer_request.response?.pointed ?: run {
            println("error: cannot find framebuffer.")
            return
        }

        val count = framebuffer.framebuffer_count
        for (index in 0..count.toLong()) {
            val entry = (framebuffer.framebuffers?.get(index) ?: continue).pointed
            installTtyDevice(index,entry)
        }
    }
}