@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.drm

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.mem.dmabuf.DmaBuffer
import org.plos_clan.cpos.utils.LittleEndianBuffer
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

internal class DrmClip(val left: UInt, val top: UInt, val right: UInt, val bottom: UInt) {
    constructor(left: UInt, top: UInt, mode: DrmMode) :
        this(left, top, left + mode.width.toUInt(), top + mode.height.toUInt())

    fun fits(framebuffer: DrmFramebuffer): Boolean =
        left < right && top < bottom && right <= framebuffer.width && bottom <= framebuffer.height
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

    fun damage(bytes: ByteArray): List<DrmClip>? {
        if (bytes.size % 8 != 0) return null
        val data = LittleEndianBuffer(bytes)
        return List(bytes.size / 8) { index ->
            val start = index * 8
            val left = data.readU16(start).toUInt()
            val top = data.readU16(start + 2).toUInt()
            val right = data.readU16(start + 4).toUInt()
            val bottom = data.readU16(start + 6).toUInt()
            val clip = DrmClip(left, top, right, bottom)
            if (!clip.fits(this)) return null
            clip
        }
    }

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
    val viewport: DrmClip,
) {
    init {
        check(framebuffer.buffer.retain())
    }

    fun close() = framebuffer.buffer.release()

    fun copyTo(target: CPointer<UByteVar>, pitch: Int, clip: DrmClip = viewport) {
        val left = maxOf(viewport.left, clip.left)
        val top = maxOf(viewport.top, clip.top)
        val right = minOf(viewport.right, clip.right)
        val bottom = minOf(viewport.bottom, clip.bottom)
        if (left >= right || top >= bottom) return

        val pixelBytes = framebuffer.format.bitsPerPixel / 8u
        val rowBytes = ((right - left) * pixelBytes).toInt()
        val rows = (bottom - top).toInt()
        val sourceRow = framebuffer.offset + top.toULong() * framebuffer.pitch
        val targetRow = (top - viewport.top).toInt() * pitch
        var source = sourceRow + left.toULong() * pixelBytes
        var offset = targetRow + ((left - viewport.left) * pixelBytes).toInt()
        if (rowBytes == pitch && rowBytes.toUInt() == framebuffer.pitch) {
            val destination = checkNotNull(target + offset)
            framebuffer.buffer.copyTo(source, destination, rows * rowBytes)
            return
        }
        repeat(rows) {
            val destination = checkNotNull(target + offset)
            framebuffer.buffer.copyTo(source, destination, rowBytes)
            source += framebuffer.pitch
            offset += pitch
        }
    }

    companion object {
        fun create(framebuffer: DrmFramebuffer, id: UInt, viewport: DrmClip): DrmScanout? {
            if (!viewport.fits(framebuffer)) return null
            return DrmScanout(framebuffer, id, viewport)
        }
    }
}
