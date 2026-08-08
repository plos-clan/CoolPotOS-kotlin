@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memcpy

class UserMemory private constructor(
    private val pageDirectory: PageDirectory,
    private val address: ULong,
    private val addressSpace: VirtualAddressSpace?,
) {
    internal constructor(pageDirectory: PageDirectory, address: ULong) :
        this(pageDirectory, address, null)

    internal constructor(addressSpace: VirtualAddressSpace, address: ULong) :
        this(addressSpace.pageDirectory, address, addressSpace)

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
        if (!isValidRange(destination, destinationOffset, size)) {
            return false
        }
        if (size == 0) return true

        return destination.usePinned { target ->
            var copied = 0
            while (copied < size) {
                val currentAddress = address + copied.toULong()
                val physicalAddress = resolveUserPhysicalAddress(currentAddress, false)
                    ?: return@usePinned false
                val source = physicalAddress.toVirtualPointer<UByteVar>()
                    ?: return@usePinned false
                val chunk = pageChunkSize(currentAddress, size - copied)
                memcpy(
                    target.addressOf(destinationOffset + copied),
                    source,
                    chunk.toULong(),
                )
                copied += chunk
            }
            true
        }
    }

    fun copyToUser(
        data: ByteArray,
        sourceOffset: Int = 0,
        size: Int = data.size - sourceOffset,
    ): Boolean {
        if (!isValidRange(data, sourceOffset, size)) {
            return false
        }
        if (size == 0) return true

        return data.usePinned { source ->
            var copied = 0
            while (copied < size) {
                val currentAddress = address + copied.toULong()
                val physicalAddress = resolveUserPhysicalAddress(currentAddress, true)
                    ?: return@usePinned false
                val destination = physicalAddress.toVirtualPointer<UByteVar>()
                    ?: return@usePinned false
                val chunk = pageChunkSize(currentAddress, size - copied)
                memcpy(
                    destination,
                    source.addressOf(sourceOffset + copied),
                    chunk.toULong(),
                )
                copied += chunk
            }
            true
        }
    }

    fun isWritable(size: Int): Boolean {
        if (!validUserRange(size)) return false
        var checked = 0
        while (checked < size) {
            val currentAddress = address + checked.toULong()
            if (resolveUserPhysicalAddress(currentAddress, true) == null) return false
            checked += pageChunkSize(currentAddress, size - checked)
        }
        return true
    }

    fun readUIntLE(): UInt? {
        if (!validUserRange(UInt.SIZE_BYTES)) return null
        var value = 0u
        repeat(UInt.SIZE_BYTES) { index ->
            val currentAddress = address + index.toULong()
            val physicalAddress = resolveUserPhysicalAddress(currentAddress, false)
                ?: return null
            val source = physicalAddress.toVirtualPointer<UByteVar>() ?: return null
            value = value or (source[0].toUInt() shl (index * Byte.SIZE_BITS))
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
            val physicalAddress = resolveUserPhysicalAddress(
                virtualAddress = currentAddress,
                requireWritable = false,
            ) ?: return null
            val source = physicalAddress.toVirtualPointer<UByteVar>() ?: return null
            val pageOffset = currentAddress and (PAGE_SIZE_BYTES - 1uL)
            val chunkLength = minOf(
                maxLength - copied,
                (PAGE_SIZE_BYTES - pageOffset).toInt(),
            )

            repeat(chunkLength) { index ->
                val byte = source[index].toByte()
                if (byte == 0.toByte()) {
                    return result.copyOf(copied + index)
                }
                result[copied + index] = byte
            }
            copied += chunkLength
            currentAddress += chunkLength.toULong()
        }
        return null
    }

    fun getAddress(): ULong = address

    private fun resolveUserPhysicalAddress(
        virtualAddress: ULong,
        requireWritable: Boolean,
    ): ULong? {
        pageDirectory.resolveUserPhysicalAddress(virtualAddress, requireWritable)?.let {
            return it
        }
        val owner = addressSpace ?: return null
        if (owner.faultIn(virtualAddress, write = requireWritable) != PageFaultResult.RESOLVED) {
            return null
        }
        return pageDirectory.resolveUserPhysicalAddress(virtualAddress, requireWritable)
    }

    private fun isValidRange(buffer: ByteArray, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= buffer.size - size && validUserRange(size)

    private fun validUserRange(size: Int): Boolean =
        size >= 0 &&
            (size == 0 ||
                address < USER_VIRTUAL_ADDRESS_LIMIT &&
                size.toULong() <= USER_VIRTUAL_ADDRESS_LIMIT - address)

    private fun pageChunkSize(currentAddress: ULong, remaining: Int): Int =
        minOf(
            remaining,
            (PAGE_SIZE_BYTES - (currentAddress and (PAGE_SIZE_BYTES - 1uL))).toInt(),
        )
}
