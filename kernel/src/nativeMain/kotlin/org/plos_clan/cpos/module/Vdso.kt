@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.CachedRegionBacking
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_END
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignUp

object Vdso : CachedRegionBacking() {
    private var image = ByteArray(0)

    internal val signalTerminateAddress: ULong
        get() {
            if (image.isEmpty()) return 0uL
            val offset = image.size - SIGNAL_TERMINATE.size
            return USER_MMAP_END + offset.toULong()
        }

    internal val signalCaptureAddress: ULong
        get() = if (image.isEmpty()) 0uL else signalTerminateAddress - SIGNAL_CAPTURE.size.toULong()

    fun initialize(): Boolean {
        val start = bridge.embedded_vdso_start
        val size = bridge.embedded_vdso_end.toLong() - start.toLong()
        val clockSize = sizeOf<bridge.vdso_clock_data_t>()
        val padding = 15
        val maxSize = Int.MAX_VALUE - padding - SIGNAL_CAPTURE.size - SIGNAL_TERMINATE.size
        if (!TscClock.isReady || size !in clockSize..maxSize.toLong()) return false
        val capture = (size.toInt() + padding) and padding.inv()
        val terminate = capture + SIGNAL_CAPTURE.size
        val bytes = ByteArray(terminate + SIGNAL_TERMINATE.size)
        val monotonic = TscClock.nanoTime()
        val realtime = RealtimeClock.atMonotonic(monotonic)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), start, size.toULong())
            val offset = (size - clockSize).toInt()
            val clock = pinned.addressOf(offset).reinterpret<bridge.vdso_clock_data_t>()
            bridge.runtime_clock_snapshot(clock)
            clock.pointed.realtime_seconds = realtime.seconds
            clock.pointed.realtime_epoch = monotonic - realtime.nanoseconds.toULong()
        }
        SIGNAL_CAPTURE.copyInto(bytes, capture)
        SIGNAL_TERMINATE.copyInto(bytes, terminate)
        image = bytes
        return true
    }

    fun install(addressSpace: AddressSpace): Boolean {
        if (image.isEmpty()) return false
        val length = image.size.toULong().alignUp(PAGE_SIZE_BYTES) ?: return false
        val region = MemoryRegion(
            start = USER_MMAP_END,
            end = USER_MMAP_END + length,
            access = MEMORY_REGION_READABLE or MEMORY_REGION_EXECUTABLE,
            maximumAccess = MEMORY_REGION_READABLE or MEMORY_REGION_EXECUTABLE,
            name = "[vdso]",
            type = MemoryRegionType.VDSO,
            shared = true,
            backing = this,
            sharedIdentity = this,
        )
        return addressSpace.insert(region)
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
