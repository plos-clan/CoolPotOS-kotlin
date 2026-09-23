@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memcpy
import platform.posix.memmove
import platform.posix.memset

class UserMemory internal constructor(
    private val addressSpace: AddressSpace,
    val address: ULong,
) : NativeBuffer(), IoBuffer {
    fun copyFromUser(size: Int): ByteArray? {
        if (size < 0) {
            return null
        }
        val destination = ByteArray(size)
        return if (copyFromUser(destination)) destination else null
    }

    fun copyFromUser(
        destination: ByteArray,
        destinationOffset: Int = 0,
        size: Int = destination.size - destinationOffset,
    ): Boolean {
        if (!isValidRange(destination, destinationOffset, size)) return false
        return copyTo(0, destination, destinationOffset, size) == size
    }

    fun copyToUser(
        data: ByteArray,
        sourceOffset: Int = 0,
        size: Int = data.size - sourceOffset,
    ): Boolean {
        if (!isValidRange(data, sourceOffset, size)) return false
        return copyFrom(0, data, sourceOffset, size) == size
    }

    override fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int {
        if (!isValidRange(destination, destinationOffset, count) || sourceOffset < 0) return 0
        return destination.usePinned { target ->
            transfer(sourceOffset, count, false) { source, copied, chunk ->
                memcpy(
                    target.addressOf(destinationOffset + copied),
                    source,
                    chunk.toULong(),
                )
                chunk
            }
        }
    }

    override fun copyToNative(sourceOffset: Int, destination: CPointer<UByteVar>, count: Int): Int =
        transfer(sourceOffset, count, false) { source, copied, chunk ->
            memmove(requireNotNull(destination + copied), source, chunk.toULong())
            chunk
        }

    override fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int {
        if (!isValidRange(source, sourceOffset, count) || destinationOffset < 0) return 0
        return source.usePinned { input ->
            transfer(destinationOffset, count, true) { destination, copied, chunk ->
                memcpy(
                    destination,
                    input.addressOf(sourceOffset + copied),
                    chunk.toULong(),
                )
                chunk
            }
        }
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
        if (destinationOffset < 0 || count < 0) return 0
        return transfer(destinationOffset, count, true) { destination, _, chunk ->
            memset(destination, value.toInt(), chunk.toULong())
            chunk
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: BufferSource,
        sourceOffset: Int,
        count: Int,
    ): Int {
        if (source !is NativeBuffer) return source.copyTo(sourceOffset, this, destinationOffset, count)
        return transfer(destinationOffset, count, true, pin = true) { destination, copied, chunk ->
            source.copyToNative(sourceOffset + copied, destination, chunk)
        }
    }

    private fun prepare(offset: Int, count: Int, writable: Boolean): Boolean {
        if (!validUserRange(offset, count)) return false
        if (count == 0) return true

        val start = address + offset.toULong()
        val firstPage = start.alignDown(PAGE_SIZE_BYTES)
        val lastPage = (start + count.toULong() - 1uL).alignDown(PAGE_SIZE_BYTES)
        var virtualPage = firstPage
        while (virtualPage <= lastPage) {
            val ready = addressSpace.accessUserPage(virtualPage, writable, pin = false) { 1 }
            if (ready == 0) return false
            virtualPage += PAGE_SIZE_BYTES
        }
        return true
    }

    fun isWritable(size: Int): Boolean = prepare(0, size, true)

    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (prepare(offset, count, false)) PreparedBufferSource(this) else null

    override fun prepareWrite(
        offset: Int,
        count: Int,
        faultPolicy: BufferFaultPolicy,
    ): PreparedBufferDestination? {
        val ready = when (faultPolicy) {
            BufferFaultPolicy.PREFAULT -> prepare(offset, count, true)
            BufferFaultPolicy.ON_ACCESS -> validUserRange(offset, count)
        }
        return if (ready) PreparedBufferDestination(this) else null
    }

    fun readUIntLE(): UInt? {
        val bytes = copyFromUser(UInt.SIZE_BYTES) ?: return null
        var value = 0u
        for (index in bytes.indices) {
            value = value or (bytes[index].toUByte().toUInt() shl (index * Byte.SIZE_BITS))
        }
        return value
    }

    fun copyCStringFromUser(maxLength: Int): ByteArray? {
        if (maxLength <= 0 || address >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return null
        }
        val result = ByteArray(maxLength)
        var copied = 0
        var currentAddress = address
        while (copied < maxLength && currentAddress < USER_VIRTUAL_ADDRESS_LIMIT) {
            val physicalAddress = addressSpace.acquireUserFrame(currentAddress, false) ?: return null
            val source = checkNotNull(physicalAddress.toVirtualPointer<UByteVar>())
            val pageOffset = currentAddress - currentAddress.alignDown(PAGE_SIZE_BYTES)
            val chunkLength = minOf(
                maxLength - copied,
                (PAGE_SIZE_BYTES - pageOffset).toInt(),
            )

            try {
                repeat(chunkLength) { index ->
                    val byte = source[index].toByte()
                    if (byte == 0.toByte()) return result.copyOf(copied + index)
                    result[copied + index] = byte
                }
            } finally {
                UserFrameReferences.release(physicalAddress.alignDown(PAGE_SIZE_BYTES))
            }
            copied += chunkLength
            currentAddress += chunkLength.toULong()
        }
        return null
    }

    fun copyNativeStructArrayToUser(
        values: Array<out NativeStruct>,
    ): Boolean {
        val chunks = Array(values.size) { values[it].toNativeBytes() }

        var totalSize = 0
        for (chunk in chunks) {
            if (chunk.size > Int.MAX_VALUE - totalSize) return false
            totalSize += chunk.size
        }

        val destination = prepareWrite(0, totalSize) ?: return false

        var offset = 0
        for (chunk in chunks) {
            val copied = destination.copyFrom(
                destinationOffset = offset,
                source = chunk,
                sourceOffset = 0,
                count = chunk.size,
            )
            if (copied != chunk.size) return false
            offset += chunk.size
        }

        return true
    }

    private fun isValidRange(buffer: ByteArray, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= buffer.size - size && validUserRange(size)

    private fun validUserRange(size: Int): Boolean =
        validUserRange(0, size)

    private fun validUserRange(offset: Int, size: Int): Boolean {
        if (offset < 0 || size < 0) return false
        if (size == 0) return true
        if (address >= USER_VIRTUAL_ADDRESS_LIMIT) return false
        val available = USER_VIRTUAL_ADDRESS_LIMIT - address
        return offset.toULong() <= available && size.toULong() <= available - offset.toULong()
    }

    private inline fun transfer(
        offset: Int,
        count: Int,
        requireWritable: Boolean,
        pin: Boolean = false,
        operation: (CPointer<UByteVar>, Int, Int) -> Int,
    ): Int {
        if (!validUserRange(offset, count)) return 0
        var copied = 0
        while (copied < count) {
            val currentAddress = address + offset.toULong() + copied.toULong()
            val chunk = pageChunkSize(currentAddress, count - copied)
            val transferred = addressSpace.accessUserPage(currentAddress, requireWritable, pin) { physical ->
                val pointer = checkNotNull(physical.toVirtualPointer<UByteVar>())
                operation(pointer, copied, chunk)
            }
            if (transferred !in 1..chunk) break
            copied += transferred
            if (transferred < chunk) break
        }
        return copied
    }

    private fun pageChunkSize(currentAddress: ULong, remaining: Int): Int =
        minOf(
            remaining,
            (PAGE_SIZE_BYTES - (currentAddress - currentAddress.alignDown(PAGE_SIZE_BYTES))).toInt(),
        )
}
