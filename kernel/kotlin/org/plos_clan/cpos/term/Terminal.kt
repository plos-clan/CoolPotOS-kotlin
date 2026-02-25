@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.term

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.set

private data class TerminalSurface(
    val pixels: CPointer<UIntVar>,
    val width: Int,
    val height: Int,
    val stride: Int,
)

object Terminal {
    private const val DEFAULT_BACKGROUND_COLOR = 0xFF000000u
    private var surface: TerminalSurface? = null
    private var backgroundColor: UInt = DEFAULT_BACKGROUND_COLOR

    fun initialize(
        pixels: CPointer<UIntVar>,
        width: Int,
        height: Int,
        stride: Int,
    ) {
        surface = TerminalSurface(
            pixels = pixels,
            width = width,
            height = height,
            stride = stride,
        )
        clear()
    }

    fun clear() {
        val framebuffer = surface ?: return
        for (row in 0 until framebuffer.height) {
            val rowOffset = row * framebuffer.stride
            for (column in 0 until framebuffer.width) {
                framebuffer.pixels[rowOffset + column] = backgroundColor
            }
        }
    }
}
