package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal enum class DrmPixelLayout(val bitsPerPixel: UInt) {
    RGB888(32u),
    BGR888(32u),
    RGB565(16u),
}

internal enum class DrmFormat(val fourcc: UInt, val layout: DrmPixelLayout, val depth: UInt) {
    XRGB8888(0x34325258u, DrmPixelLayout.RGB888, 24u),
    ARGB8888(0x34325241u, DrmPixelLayout.RGB888, 32u),
    XBGR8888(0x34324258u, DrmPixelLayout.BGR888, 24u),
    ABGR8888(0x34324241u, DrmPixelLayout.BGR888, 32u),
    RGB565(0x36314752u, DrmPixelLayout.RGB565, 16u);

    val bitsPerPixel: UInt get() = layout.bitsPerPixel

    companion object {
        fun legacy(bitsPerPixel: UInt, depth: UInt): DrmFormat? = when {
            bitsPerPixel == 32u && depth == 24u -> XRGB8888
            bitsPerPixel == 32u && depth == 32u -> ARGB8888
            bitsPerPixel == 16u && depth == 16u -> RGB565
            else -> null
        }
    }
}

internal class DrmMode(val width: Int, val height: Int) {
    val name = "${width}x$height"
    val bytes = ByteArray(68)

    init {
        require(width in 1..UShort.MAX_VALUE.toInt() && height in 1..UShort.MAX_VALUE.toInt())
        val data = LittleEndianBuffer(bytes)
        val clock = width.toLong() * height * 60 / 1000
        data.writeU32(0, clock.toUInt())
        for (offset in intArrayOf(4, 6, 8, 10)) data.writeU16(offset, width.toUShort())
        for (offset in intArrayOf(14, 16, 18, 20)) data.writeU16(offset, height.toUShort())
        data.writeU32(24, 60u)
        data.writeU32(32, 0x48u)
        name.encodeToByteArray().copyInto(bytes, 36)
    }
}

internal abstract class DrmOutput(val mode: DrmMode, val format: DrmFormat) {
    val formats = DrmFormat.entries.filter { it.layout == format.layout }

    abstract fun present(scanout: DrmScanout)
    abstract fun blank()
    abstract fun disable()
}
