@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.drm

import kotlinx.cinterop.plus
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.time.MonotonicClock
import platform.posix.memset

internal class BootDisplay private constructor(
    private val device: TtyGraphicsDevice,
    mode: DrmMode,
    format: DrmFormat,
    private val restoreConsole: () -> Unit,
) : DrmOutput(mode, format) {
    override fun blank() = device.presentGraphics { target ->
        memset(target, 0, device.pitch * device.height)
        Unit
    }

    override fun present(scanout: DrmScanout) = device.presentGraphics { target ->
        val framebuffer = scanout.framebuffer
        val pixelBytes = format.bitsPerPixel / 8u
        val rowBytes = mode.width * pixelBytes.toInt()
        val sourceStart = framebuffer.offset.toULong() +
            scanout.y.toULong() * framebuffer.pitch + scanout.x.toULong() * pixelBytes
        for (row in 0 until mode.height) {
            val sourceOffset = sourceStart + row.toULong() * framebuffer.pitch
            val targetOffset = (row.toULong() * device.pitch).toInt()
            val destination = checkNotNull(target + targetOffset)
            framebuffer.buffer.copyTo(sourceOffset, destination, rowBytes)
        }
    }

    fun register(index: Int, clock: MonotonicClock) {
        val uevent = SysfsTextAttribute.constant("uevent", "DRIVER=cpos-boot\nMODALIAS=platform:cpos-boot\n")
        val bus = SysfsIndexBinding("platform")
        val bindings = SysfsBindings(bus = bus)
        val spec = SysfsObjectSpec(
            name = "boot-framebuffer.$index",
            attributes = listOf(uevent),
            bindings = bindings,
        )
        val parent = when (val result = Sysfs.registerObject(spec)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return
        }
        val drm = DrmDevice(this, clock)
        drm.register(index, parent)
    }

    override fun disable() {
        device.releaseGraphics()
        restoreConsole()
    }

    companion object {
        fun create(
            device: TtyGraphicsDevice,
            bitsPerPixel: UShort,
            restoreConsole: () -> Unit,
        ): BootDisplay? {
            if (device.address == null || device.width !in 1uL..65535uL || device.height !in 1uL..65535uL) return null
            val format = when {
                bitsPerPixel == 32.toUShort() && device.redMaskSize == 8.toUByte() &&
                    device.greenMaskSize == 8.toUByte() && device.blueMaskSize == 8.toUByte() &&
                    device.greenMaskShift == 8.toUByte() -> when {
                    device.redMaskShift == 16.toUByte() && device.blueMaskShift == 0.toUByte() -> DrmFormat.XRGB8888
                    device.redMaskShift == 0.toUByte() && device.blueMaskShift == 16.toUByte() -> DrmFormat.XBGR8888
                    else -> return null
                }
                bitsPerPixel == 16.toUShort() && device.redMaskSize == 5.toUByte() &&
                    device.greenMaskSize == 6.toUByte() && device.blueMaskSize == 5.toUByte() &&
                    device.redMaskShift == 11.toUByte() && device.greenMaskShift == 5.toUByte() &&
                    device.blueMaskShift == 0.toUByte() -> DrmFormat.RGB565
                else -> return null
            }
            val rowSize = device.width * (format.bitsPerPixel / 8u)
            if (device.pitch < rowSize || device.pitch > Int.MAX_VALUE.toULong() / device.height) return null
            val mode = DrmMode(device.width.toInt(), device.height.toInt())
            return BootDisplay(device, mode, format, restoreConsole)
        }
    }
}
