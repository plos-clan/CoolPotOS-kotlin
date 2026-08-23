@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.PageFaultResult
import org.plos_clan.cpos.mem.page.PageDirectory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memcpy
import platform.posix.memset

class UserMemory private constructor(
    private val pageDirectory: PageDirectory,
    private val address: ULong,
    private val addressSpace: AddressSpace?,
) : IoBuffer {
    private var preparedVirtualPage = ULong.MAX_VALUE
    private var preparedWritable = false
    private var firstPhysicalPage = 0uL
    private var additionalPhysicalPages: ULongArray? = null

    internal constructor(pageDirectory: PageDirectory, address: ULong) :
        this(pageDirectory, address, null)

    internal constructor(addressSpace: AddressSpace, address: ULong) :
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
                bridge.close_smap()
                memcpy(
                    target.addressOf(destinationOffset + copied),
                    source,
                    chunk.toULong(),
                )
                bridge.open_smap()
            }
        }
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
                bridge.close_smap()
                memcpy(
                    destination,
                    input.addressOf(sourceOffset + copied),
                    chunk.toULong(),
                )
                bridge.open_smap()
            }
        }
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int {
        if (destinationOffset < 0 || count < 0) return 0
        return transfer(destinationOffset, count, true) { destination, _, chunk ->
            bridge.close_smap()
            memset(destination, value.toInt(), chunk.toULong())
            bridge.open_smap()
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: CPointer<UByteVar>,
        count: Int,
    ): Int = transfer(destinationOffset, count, true) { destination, copied, chunk ->
        bridge.close_smap()
        memcpy(destination, requireNotNull(source + copied), chunk.toULong())
        bridge.open_smap()
    }

    private fun prepare(offset: Int, count: Int, writable: Boolean): Boolean {
        if (!validUserRange(offset, count)) return false
        if (count == 0) return true

        val start = address + offset.toULong()
        val firstPage = start.alignDown(PAGE_SIZE_BYTES)
        val lastPage = (start + count.toULong() - 1uL).alignDown(PAGE_SIZE_BYTES)
        if (isPrepared(firstPage, lastPage, writable)) return true

        preparedVirtualPage = ULong.MAX_VALUE
        additionalPhysicalPages = null
        val pageCount = ((lastPage - firstPage) / PAGE_SIZE_BYTES).toInt() + 1
        val additional = if (pageCount > 1) ULongArray(pageCount - 1) else null
        repeat(pageCount) { index ->
            val virtualPage = firstPage + index.toULong() * PAGE_SIZE_BYTES
            val physicalPage = resolveUserPhysicalAddress(virtualPage, writable)
                ?: return false
            if (index == 0) firstPhysicalPage = physicalPage
            else additional!![index - 1] = physicalPage
        }
        preparedVirtualPage = firstPage
        preparedWritable = writable
        additionalPhysicalPages = additional
        return true
    }

    fun isWritable(size: Int): Boolean = prepare(0, size, true)

    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (prepare(offset, count, false)) PreparedBufferSource(this) else null

    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (prepare(offset, count, true)) PreparedBufferDestination(this) else null

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
        bridge.close_smap()

        val result = ByteArray(maxLength)
        var copied = 0
        var currentAddress = address
        while (copied < maxLength && currentAddress < USER_VIRTUAL_ADDRESS_LIMIT) {
            val physicalAddress = resolveUserPhysicalAddress(
                virtualAddress = currentAddress,
                requireWritable = false,
            ) ?: return null
            val source = physicalAddress.toVirtualPointer<UByteVar>() ?: run {
                bridge.open_smap()
                return null
            }
            val pageOffset = currentAddress - currentAddress.alignDown(PAGE_SIZE_BYTES)
            val chunkLength = minOf(
                maxLength - copied,
                (PAGE_SIZE_BYTES - pageOffset).toInt(),
            )

            repeat(chunkLength) { index ->
                val byte = source[index].toByte()
                if (byte == 0.toByte()) {
                    bridge.open_smap()
                    return result.copyOf(copied + index)
                }
                result[copied + index] = byte
            }
            copied += chunkLength
            currentAddress += chunkLength.toULong()
        }
        bridge.open_smap()
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
        operation: (CPointer<UByteVar>, Int, Int) -> Unit,
    ): Int {
        if (!validUserRange(offset, count)) return 0
        var copied = 0
        while (copied < count) {
            val currentAddress = address + offset.toULong() + copied.toULong()
            val physicalAddress = preparedPhysicalAddress(currentAddress, requireWritable)
                ?: resolveUserPhysicalAddress(currentAddress, requireWritable)
                ?: break
            val pointer = physicalAddress.toVirtualPointer<UByteVar>() ?: break
            val chunk = pageChunkSize(currentAddress, count - copied)
            operation(pointer, copied, chunk)
            copied += chunk
        }
        return copied
    }

    private fun preparedPhysicalAddress(
        virtualAddress: ULong,
        requireWritable: Boolean,
    ): ULong? {
        if (preparedVirtualPage == ULong.MAX_VALUE || requireWritable && !preparedWritable) return null

        val virtualPage = virtualAddress.alignDown(PAGE_SIZE_BYTES)
        if (virtualPage < preparedVirtualPage) return null
        val pageIndex = (virtualPage - preparedVirtualPage) / PAGE_SIZE_BYTES
        val physicalPage = if (pageIndex == 0uL) {
            firstPhysicalPage
        } else {
            additionalPhysicalPages?.getOrNull(pageIndex.toInt() - 1) ?: return null
        }
        return physicalPage + (virtualAddress - virtualPage)
    }

    private fun isPrepared(firstPage: ULong, lastPage: ULong, writable: Boolean): Boolean {
        if (preparedVirtualPage == ULong.MAX_VALUE || writable && !preparedWritable ||
            firstPage < preparedVirtualPage
        ) return false
        val additionalPageCount = additionalPhysicalPages?.size?.toULong() ?: 0uL
        return lastPage <= preparedVirtualPage + additionalPageCount * PAGE_SIZE_BYTES
    }

    private fun pageChunkSize(currentAddress: ULong, remaining: Int): Int =
        minOf(
            remaining,
            (PAGE_SIZE_BYTES - (currentAddress - currentAddress.alignDown(PAGE_SIZE_BYTES))).toInt(),
        )
}
