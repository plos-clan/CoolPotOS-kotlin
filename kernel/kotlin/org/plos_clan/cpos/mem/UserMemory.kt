@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toVirtualPointer

class UserMemory internal constructor(
    private val pageDirectory: PageDirectory,
    private val address: ULong,
) {
    fun copyFromUser(size: Int): ByteArray? {
        val chunks = resolveRange(size, requireWritable = false) ?: return null
        val destination = ByteArray(size)

        for (chunk in chunks) {
            val source = chunk.physicalAddress.toVirtualPointer<UByteVar>() ?: return null
            repeat(chunk.length) { index ->
                destination[chunk.bufferOffset + index] = source[index].toByte()
            }
        }
        return destination
    }

    fun copyToUser(data: ByteArray): Boolean {
        val chunks = resolveRange(data.size, requireWritable = true) ?: return false

        for (chunk in chunks) {
            val destination = chunk.physicalAddress.toVirtualPointer<UByteVar>() ?: return false
            repeat(chunk.length) { index ->
                destination[index] = data[chunk.bufferOffset + index].toUByte()
            }
        }
        return true
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
            val physicalAddress = pageDirectory.resolveUserPhysicalAddress(
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
}

private data class UserMemoryChunk(
    val physicalAddress: ULong,
    val bufferOffset: Int,
    val length: Int,
)
