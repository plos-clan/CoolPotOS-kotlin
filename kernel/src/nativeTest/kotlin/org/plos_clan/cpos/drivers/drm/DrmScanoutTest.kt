@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.drm

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.ResidentPage
import org.plos_clan.cpos.mem.dmabuf.CpuDmaBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DrmScanoutTest {
    private class Pixels(pitch: UInt, offset: UInt) : AutoCloseable {
        private val pages = List(2) { assertNotNull(ResidentPage.allocate()) }
        private val buffer = object : CpuDmaBuffer(pages) {
            override fun close() = pages.forEach(ResidentPage::release)
        }
        val framebuffer: DrmFramebuffer

        init {
            val bytes = ByteArray(64) { it.toByte() }
            val boundary = PAGE_SIZE_BYTES.toInt()
            val first = minOf(bytes.size, boundary - offset.toInt())
            pages.first().copyFrom(offset.toInt(), bytes, 0, first)
            pages.last().copyFrom(0, bytes, first, bytes.size - first)
            val frame = DrmFramebuffer.create(buffer, 4u, 4u, pitch, offset, DrmFormat.RGB565)
            framebuffer = assertNotNull(frame)
        }

        override fun close() {
            framebuffer.close()
            buffer.release()
        }
    }

    @Test
    fun packedViewportCrossesSourcePages() {
        Pixels(8u, PAGE_SIZE_BYTES.toUInt() - 5u).use { pixels ->
            val viewport = DrmClip(0u, 0u, 4u, 4u)
            val scanout = assertNotNull(DrmScanout.create(pixels.framebuffer, 1u, viewport))
            val target = ByteArray(32)
            target.usePinned { scanout.copyTo(it.addressOf(0).reinterpret(), 8) }
            scanout.close()
            assertContentEquals(ByteArray(32) { it.toByte() }, target)
        }
    }

    @Test
    fun damageIntersectsPannedViewportAndPreservesPadding() {
        Pixels(12u, 4u).use { pixels ->
            val viewport = DrmClip(1u, 1u, 4u, 3u)
            val scanout = assertNotNull(DrmScanout.create(pixels.framebuffer, 1u, viewport))
            val bytes = byteArrayOf(0, 0, 0, 0, 2, 0, 2, 0, 3, 0, 2, 0, 4, 0, 4, 0)
            val damage = assertNotNull(pixels.framebuffer.damage(bytes))
            val target = ByteArray(16) { -1 }
            target.usePinned { pinned ->
                val address = pinned.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
                for (clip in damage) scanout.copyTo(address, 8, clip)
            }
            scanout.close()
            val expected = byteArrayOf(14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 30, 31, -1, -1)
            assertContentEquals(expected, target)
            bytes[12] = 5
            assertNull(pixels.framebuffer.damage(bytes))
        }
    }
}
