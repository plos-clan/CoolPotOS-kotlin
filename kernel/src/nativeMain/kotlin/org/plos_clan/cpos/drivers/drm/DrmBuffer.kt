package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.mem.dmabuf.DmaBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignUp

internal class DumbLayout private constructor(val pitch: UInt, val size: ULong) {
    companion object {
        fun create(width: UInt, height: UInt, bitsPerPixel: UInt): DumbLayout? {
            if (width == 0u || height == 0u || bitsPerPixel == 0u) return null
            val rowBits = width.toULong() * bitsPerPixel
            val rowBytes = (rowBits + 7uL) / 8uL
            val pitch = rowBytes.alignUp(64uL) ?: return null
            if (pitch > UInt.MAX_VALUE.toULong()) return null
            val size = (pitch * height).alignUp(PAGE_SIZE_BYTES) ?: return null
            if (size > Long.MAX_VALUE.toULong()) return null
            return DumbLayout(pitch.toUInt(), size)
        }
    }
}

internal class DrmFramebuffer private constructor(
    val buffer: DmaBuffer,
    val width: UInt,
    val height: UInt,
    val pitch: UInt,
    val offset: UInt,
    val format: DrmFormat,
) {
    init {
        check(buffer.retain())
    }

    fun close() = buffer.release()

    companion object {
        fun create(
            buffer: DmaBuffer,
            width: UInt,
            height: UInt,
            pitch: UInt,
            offset: UInt,
            format: DrmFormat,
        ): DrmFramebuffer? {
            if (width == 0u || height == 0u) return null
            val rowBytes = width.toULong() * (format.bitsPerPixel / 8u)
            if (rowBytes > pitch.toULong()) return null
            val lastRow = offset.toULong() + (height - 1u).toULong() * pitch
            if (lastRow > buffer.size || rowBytes > buffer.size - lastRow) return null
            return DrmFramebuffer(buffer, width, height, pitch, offset, format)
        }
    }
}

internal class DrmScanout private constructor(
    val framebuffer: DrmFramebuffer,
    val framebufferId: UInt,
    val x: UInt,
    val y: UInt,
) {
    init {
        check(framebuffer.buffer.retain())
    }

    fun close() = framebuffer.buffer.release()

    companion object {
        fun create(framebuffer: DrmFramebuffer, id: UInt, x: UInt, y: UInt, mode: DrmMode): DrmScanout? {
            val fits = x.toULong() + mode.width.toUInt() <= framebuffer.width.toULong() &&
                y.toULong() + mode.height.toUInt() <= framebuffer.height.toULong()
            if (!fits) return null
            return DrmScanout(framebuffer, id, x, y)
        }
    }
}
