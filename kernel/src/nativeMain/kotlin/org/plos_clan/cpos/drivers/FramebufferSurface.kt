@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.drivers.pcie.Pcie
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.toPointer

internal abstract class FramebufferSurface {
    abstract fun present(draw: (CPointer<UByteVar>) -> Unit)

    private class Linear(private val device: TtyGraphicsDevice) : FramebufferSurface() {
        override fun present(draw: (CPointer<UByteVar>) -> Unit) = draw(checkNotNull(device.address).reinterpret())
    }

    private class Bochs(
        private val memory: MmioRegion,
        private val registers: MmioRegion,
        private val stride: ULong,
        private val height: UShort,
    ) : FramebufferSurface() {
        private var front = 0

        override fun present(draw: (CPointer<UByteVar>) -> Unit) {
            val next = front xor 1
            val offset = next.toULong() * stride
            val target = checkNotNull(memory.addressAt(offset)).value
            draw(checkNotNull(target.toPointer()))
            val yOffset = checkNotNull(registers.addressAt(0x512u, 2))
            yOffset.writeU16(if (next == 0) 0u else height)
            front = next
        }
    }

    companion object {
        fun create(device: TtyGraphicsDevice): FramebufferSurface = bochs(device) ?: Linear(device)

        private fun bochs(device: TtyGraphicsDevice): FramebufferSurface? {
            val pointer = device.address ?: return null
            val physical = pointer.rawValue.toLong().toULong() - Hhdm.offset
            val pci = Pcie.enumeratedDevices.firstOrNull {
                it.vendorId == 0x1234.toUShort() && it.deviceId == 0x1111.toUShort() &&
                    it.bars[0]?.address == physical
            } ?: return null
            val vram = pci.bars[0] ?: return null
            val control = pci.bars[2] ?: return null
            if (control.size < 0x516uL || device.height !in 1uL..32767uL) return null
            val stride = device.pitch * device.height
            if (stride == 0uL || stride > vram.size / 2u) return null
            val registers = MmioRegion.map(control.address, control.size) ?: return null
            val base = checkNotNull(registers.addressAt(0x500u, 22))
            val enabled = (base + 8u).readU16().toInt() and 1 != 0
            val width = (base + 2u).readU16().toULong()
            val height = (base + 4u).readU16().toULong()
            val pixelBytes = (base + 6u).readU16().toULong() / 8u
            val pitch = (base + 12u).readU16().toULong() * pixelBytes
            val offsetX = (base + 16u).readU16().toInt()
            val offsetY = (base + 18u).readU16().toInt()
            val matches = enabled && width == device.width && height == device.height &&
                pitch == device.pitch && offsetX == 0 && offsetY == 0
            val memory = if (matches) MmioRegion.map(vram.address, stride * 2u) else null
            if (memory == null) {
                registers.free()
                return null
            }
            println("Framebuffer: Bochs scanout with two complete frames")
            return Bochs(memory, registers, stride, height.toUShort())
        }
    }
}
