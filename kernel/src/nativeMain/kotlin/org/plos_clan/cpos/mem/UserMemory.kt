@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.page.UserFrameReferences
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.MutableNativeStruct
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.toVirtualPointer
import platform.posix.memchr
import platform.posix.memcpy
import platform.posix.memmove
import platform.posix.memset

class UserMemory internal constructor(
    private val addressSpace: AddressSpace,
    val address: ULong,
) : NativeBuffer(), IoBuffer {
    internal fun at(pointer: ULong) = UserMemory(addressSpace, pointer)

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
        if (count <= ULong.SIZE_BYTES) {
            return readBytes(sourceOffset, destination, destinationOffset, count)
        }
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
        if (count <= ULong.SIZE_BYTES) {
            return writeBytes(destinationOffset, source, sourceOffset, count)
        }
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

    internal inline fun <T> readRecord(
        size: Int,
        offset: Int = 0,
        decode: (LittleEndianBuffer) -> T,
    ): T? = IoScratch.use(size) { bytes ->
        if (!readInto(bytes, size, offset)) return null
        decode(LittleEndianBuffer(bytes))
    }

    internal inline fun writeRecord(
        size: Int,
        offset: Int = 0,
        encode: (LittleEndianBuffer) -> Unit,
    ): Boolean = IoScratch.use(size) { bytes ->
        bytes.fill(0, 0, size)
        encode(LittleEndianBuffer(bytes))
        writeFrom(bytes, size, offset)
    }

    internal fun readInto(bytes: ByteArray, size: Int, offset: Int = 0, start: Int = 0): Boolean {
        require(start >= 0 && size >= 0 && start <= bytes.size - size)
        return readBytes(offset, bytes, start, size) == size
    }

    internal fun writeFrom(bytes: ByteArray, size: Int, offset: Int = 0, start: Int = 0): Boolean {
        require(start >= 0 && size >= 0 && start <= bytes.size - size)
        return writeBytes(offset, bytes, start, size) == size
    }

    private fun readBytes(offset: Int, bytes: ByteArray, start: Int, size: Int): Int =
        transfer(offset, size, false) { pointer, copied, chunk ->
            NativeAbiBuffer(pointer).copyTo(bytes, start + copied, chunk)
            chunk
        }

    private fun writeBytes(offset: Int, bytes: ByteArray, start: Int, size: Int): Int =
        transfer(offset, size, true) { pointer, copied, chunk ->
            NativeAbiBuffer(pointer).copyFrom(bytes, start + copied, chunk)
            chunk
        }

    private inline fun <T> readWord(size: Int, offset: Int, decode: (ULong) -> T): T? {
        var value = 0uL
        val count = transfer(offset, size, false) { pointer, copied, chunk ->
            val bits = NativeAbiBuffer(pointer).readWord(0, chunk)
            value = value or (bits shl (copied * Byte.SIZE_BITS))
            chunk
        }
        return if (count == size) decode(value) else null
    }

    internal fun writeWord(value: ULong, size: Int, offset: Int = 0): Boolean {
        require(size in 1..ULong.SIZE_BYTES)
        return transfer(offset, size, true) { pointer, copied, chunk ->
            val bits = value shr (copied * Byte.SIZE_BITS)
            NativeAbiBuffer(pointer).writeWord(0, chunk, bits)
            chunk
        } == size
    }

    fun readUIntLE(offset: Int = 0): UInt? = readWord(UInt.SIZE_BYTES, offset) { it.toUInt() }

    fun readULongLE(offset: Int = 0): ULong? = readWord(ULong.SIZE_BYTES, offset) { it }

    fun writeUInt(value: UInt, offset: Int = 0): Boolean =
        writeWord(value.toULong(), UInt.SIZE_BYTES, offset)

    fun writeULong(value: ULong, offset: Int = 0): Boolean =
        writeWord(value, ULong.SIZE_BYTES, offset)

    internal fun writeFields(
        input: LittleEndianBuffer,
        count: Int,
        stride: Int,
        field: Int,
        width: Int,
    ): Boolean {
        require(width in 1..ULong.SIZE_BYTES && stride >= width && field in 0..stride - width)
        require(count in 0..Int.MAX_VALUE / stride)
        if (!validUserRange(0, count * stride)) return false
        var index = 0
        while (index < count) {
            val offset = index * stride + field
            val start = address + offset.toULong()
            val room = pageChunkSize(start, Int.MAX_VALUE)
            if (room < width) {
                val bits = input.readUnsigned(offset, width)
                if (!writeWord(bits, width, offset)) return false
                index++
                continue
            }
            val batch = minOf(count - index, 1 + (room - width) / stride)
            val copied = addressSpace.accessUserPage(start, writable = true, pin = false) { frame ->
                val pointer = checkNotNull(frame.toVirtualPointer<UByteVar>())
                val output = NativeAbiBuffer(pointer)
                for (entry in 0 until batch) {
                    val delta = entry * stride
                    val bits = input.readUnsigned(offset + delta, width)
                    output.writeWord(delta, width, bits)
                }
                batch
            }
            if (copied == 0) return false
            index += copied
        }
        return true
    }

    internal fun writeLongPair(first: ULong, second: ULong): Boolean {
        val size = 2 * Long.SIZE_BYTES
        if (!covers(size.toULong())) return false
        if (pageChunkSize(address, size) < size) {
            return writeULong(first) && writeULong(second, Long.SIZE_BYTES)
        }
        return addressSpace.accessUserPage(address, writable = true, pin = false) { physical ->
            val pointer = checkNotNull(physical.toVirtualPointer<UByteVar>())
            val output = NativeAbiBuffer(pointer)
            output.writeWord(0, Long.SIZE_BYTES, first)
            output.writeWord(Long.SIZE_BYTES, Long.SIZE_BYTES, second)
            size
        } == size
    }

    internal fun readStruct(value: MutableNativeStruct): Boolean =
        readRecord(value.nativeSize, decode = value::readFrom) != null

    internal fun writeStruct(value: NativeStruct, offset: Int = 0): Boolean =
        writeRecord(value.nativeSize, offset, value::writeTo)

    fun copyCStringFromUser(
        maxLength: Int,
        tooLong: VfsError = VfsError.NAME_TOO_LONG,
    ): VfsResult<ByteArray> {
        if (maxLength <= 0 || address >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return VfsResult.Err(VfsError.FAULT)
        }
        var result = ByteArray(0)
        var copied = 0
        var currentAddress = address
        while (copied < maxLength) {
            if (currentAddress >= USER_VIRTUAL_ADDRESS_LIMIT) {
                return VfsResult.Err(VfsError.FAULT)
            }
            val physicalAddress = addressSpace.acquireUserFrame(currentAddress, false)
                ?: return VfsResult.Err(VfsError.FAULT)
            val source = checkNotNull(physicalAddress.toVirtualPointer<UByteVar>())
            val pageOffset = currentAddress - currentAddress.alignDown(PAGE_SIZE_BYTES)
            val chunkLength = minOf(
                maxLength - copied,
                (PAGE_SIZE_BYTES - pageOffset).toInt(),
            )

            try {
                val terminator = memchr(source, 0, chunkLength.toULong())
                val length = if (terminator == null) chunkLength else {
                    (terminator.rawValue.toLong() - source.rawValue.toLong()).toInt()
                }
                val required = copied + length
                if (required > result.size) {
                    val doubled = minOf(maxLength.toLong(), result.size.toLong() * 2).toInt()
                    val capacity = if (terminator != null) required else maxOf(required, doubled)
                    result = result.copyOf(capacity)
                }
                if (length != 0) result.usePinned { destination ->
                    memcpy(destination.addressOf(copied), source, length.toULong())
                }
                if (terminator != null) {
                    val bytes = if (required == result.size) result else result.copyOf(required)
                    return VfsResult.Ok(bytes)
                }
            } finally {
                UserFrameReferences.release(physicalAddress.alignDown(PAGE_SIZE_BYTES))
            }
            copied += chunkLength
            currentAddress += chunkLength.toULong()
        }
        return VfsResult.Err(tooLong)
    }

    private fun isValidRange(buffer: ByteArray, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= buffer.size - size && validUserRange(0, size)

    internal fun covers(length: ULong): Boolean =
        address < USER_VIRTUAL_ADDRESS_LIMIT && length <= USER_VIRTUAL_ADDRESS_LIMIT - address

    private fun validUserRange(offset: Int, size: Int): Boolean {
        if (offset < 0 || size < 0) return false
        return size == 0 || covers(offset.toULong() + size.toULong())
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
