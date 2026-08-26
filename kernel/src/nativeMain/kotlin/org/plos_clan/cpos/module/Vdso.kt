@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.MemoryRegionBacking
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_END
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignUp

object Vdso : MemoryRegionBacking() {
    private var image = ByteArray(0)
    private var signalEntrypoints: SignalEntrypoints? = null

    internal val signalCaptureAddress: ULong
        get() = signalEntrypoints?.let { USER_MMAP_END + it.capture.toULong() } ?: 0uL

    internal val signalTerminateAddress: ULong
        get() = signalEntrypoints?.let { USER_MMAP_END + it.terminate.toULong() } ?: 0uL

    fun initialize(): Boolean = memScoped {
        val embedded = alloc<bridge.vdso_image>()
        if (!bridge.runtime_vdso_initialize(embedded.ptr)) return@memScoped false

        val size = embedded.size
        val data = embedded.data ?: return@memScoped false
        if (size == 0uL || size > Int.MAX_VALUE.toULong()) return@memScoped false

        val embeddedImage = data.reinterpret<ByteVar>().readBytes(size.toInt())
        val capture = (embeddedImage.size + 15) and 15.inv()
        val terminate = capture + SIGNAL_CAPTURE.size
        signalEntrypoints = SignalEntrypoints(capture, terminate)
        image = embeddedImage.copyOf(terminate + SIGNAL_TERMINATE.size).also { bytes ->
            SIGNAL_CAPTURE.copyInto(bytes, destinationOffset = capture)
            SIGNAL_TERMINATE.copyInto(bytes, destinationOffset = terminate)
        }
        true
    }

    fun install(addressSpace: AddressSpace): Boolean {
        if (image.isEmpty()) return false
        val length = image.size.toULong().alignUp(PAGE_SIZE_BYTES) ?: return false
        return addressSpace.insert(
            MemoryRegion(
                start = USER_MMAP_END,
                end = USER_MMAP_END + length,
                access = MEMORY_REGION_READABLE or MEMORY_REGION_EXECUTABLE,
                maximumAccess = MEMORY_REGION_READABLE or MEMORY_REGION_EXECUTABLE,
                name = "[vdso]",
                type = MemoryRegionType.VDSO,
                shared = true,
                backing = this,
                sharedIdentity = this,
            ),
        )
    }

    override fun read(offset: ULong, destination: ByteArray): Int {
        if (offset >= image.size.toULong()) return 0
        val start = offset.toInt()
        val count = minOf(destination.size, image.size - start)
        image.copyInto(destination, startIndex = start, endIndex = start + count)
        return count
    }

    override fun close() = Unit

    internal const val SIGNAL_GATEWAY_SYSCALL = 0x7fff_fffeuL

    private data class SignalEntrypoints(
        val capture: Int,
        val terminate: Int,
    )

    private val SIGNAL_CAPTURE = byteArrayOf(
        0x50, // push %rax
        0x51, // push %rcx
        0x41, 0x53, // push %r11
        0xb8.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f, // gateway syscall
        0x0f, 0x05, // syscall
        0x0f, 0x0b, // ud2 if the gateway unexpectedly returns
    )

    private val SIGNAL_TERMINATE = byteArrayOf(
        0xb8.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f, // gateway syscall
        0x0f, 0x05, // syscall
        0x0f, 0x0b, // ud2 if the gateway unexpectedly returns
    )
}
