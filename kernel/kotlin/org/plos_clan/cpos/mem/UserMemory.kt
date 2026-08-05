@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toVirtualPointer

class UserMemory private constructor(
    private val pageDirectory: PageDirectory,
    private val address: ULong,
    private val vma: VMA?,
) {
    internal constructor(pageDirectory: PageDirectory, address: ULong) :
        this(pageDirectory, address, null)

    internal constructor(vma: VMA, address: ULong) :
        this(vma.pageDirectory, address, vma)

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
        val chunks = resolveRange(size, requireWritable = false) ?: return false

        for (chunk in chunks) {
            val source = chunk.physicalAddress.toVirtualPointer<UByteVar>() ?: return false
            repeat(chunk.length) { index ->
                destination[destinationOffset + chunk.bufferOffset + index] = source[index].toByte()
            }
        }
        return true
    }

    fun copyToUser(
        data: ByteArray,
        sourceOffset: Int = 0,
        size: Int = data.size - sourceOffset,
    ): Boolean {
        if (!isValidRange(data, sourceOffset, size)) {
            return false
        }
        val chunks = resolveRange(size, requireWritable = true) ?: return false

        for (chunk in chunks) {
            val destination = chunk.physicalAddress.toVirtualPointer<UByteVar>() ?: return false
            repeat(chunk.length) { index ->
                destination[index] = data[sourceOffset + chunk.bufferOffset + index].toUByte()
            }
        }
        return true
    }

    fun isWritable(size: Int): Boolean =
        resolveRange(size, requireWritable = true) != null

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

    private fun resolveRange(
        size: Int,
        requireWritable: Boolean,
    ): List<UserMemoryChunk>? {
        if (size < 0) {
            return null
        }
        if (size == 0) {
            return emptyList()
        }

        val start = address
        val length = size.toULong()
        if (start >= USER_VIRTUAL_ADDRESS_LIMIT ||
            length > USER_VIRTUAL_ADDRESS_LIMIT - start
        ) {
            return null
        }

        val chunks = mutableListOf<UserMemoryChunk>()
        var currentAddress = start
        var bufferOffset = 0
        var remaining = size

        while (remaining > 0) {
            val physicalAddress = resolveUserPhysicalAddress(
                virtualAddress = currentAddress,
                requireWritable = requireWritable,
            ) ?: return null
            val pageOffset = currentAddress and (PAGE_SIZE_BYTES - 1uL)
            val pageRemaining = (PAGE_SIZE_BYTES - pageOffset).toInt()
            val chunkLength = minOf(remaining, pageRemaining)

            chunks += UserMemoryChunk(
                physicalAddress = physicalAddress,
                bufferOffset = bufferOffset,
                length = chunkLength,
            )
            currentAddress += chunkLength.toULong()
            bufferOffset += chunkLength
            remaining -= chunkLength
        }

        return chunks
    }

    fun getAddress() : ULong = address

    private fun resolveUserPhysicalAddress(
        virtualAddress: ULong,
        requireWritable: Boolean,
    ): ULong? {
        pageDirectory.resolveUserPhysicalAddress(virtualAddress, requireWritable)?.let {
            return it
        }
        val owner = vma ?: return null
        if (owner.faultIn(virtualAddress, write = requireWritable) != VmaFaultResult.RESOLVED) {
            return null
        }
        return pageDirectory.resolveUserPhysicalAddress(virtualAddress, requireWritable)
    }

    private fun isValidRange(buffer: ByteArray, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= buffer.size - size
}

private data class UserMemoryChunk(
    val physicalAddress: ULong,
    val bufferOffset: Int,
    val length: Int,
)
