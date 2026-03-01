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
        repeat(framebuffer.height) { row ->
            val rowOffset = row * framebuffer.stride
            repeat(framebuffer.width) { column ->
                framebuffer.pixels[rowOffset + column] = DEFAULT_BACKGROUND_COLOR
            }
        }
    }
}
