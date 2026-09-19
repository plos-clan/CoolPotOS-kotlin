package org.plos_clan.cpos.block

import kotlin.uuid.Uuid
import org.plos_clan.cpos.mem.ByteArrayBuffer

data class Partition(
    val number: Int,
    val id: Uuid,
    val type: Uuid,
    val firstBlock: ULong,
    val blockCount: ULong,
    val readOnly: Boolean,
)

class Gpt(private val device: BlockDevice) {
    private val bytes = BlockBytes(device)

    suspend fun read(): List<Partition>? {
        if (device.geometry.blockSize < 512 || device.geometry.blockCount < 6uL) return null
        return table(1uL) ?: table(device.geometry.blockCount - 1uL)
    }

    private suspend fun table(location: ULong): List<Partition>? {
        val header = read(location, device.geometry.blockSize) ?: return null
        val input = Fields(header)
        val alternate = input.u64(32)
        if (
            !header.copyOfRange(0, 8).contentEquals("EFI PART".encodeToByteArray()) ||
                input.u32(8) != 0x10000u ||
                input.u32(20) != 0u ||
                input.u64(24) != location ||
                alternate == location ||
                alternate == 0uL ||
                alternate >= device.geometry.blockCount ||
                location != 1uL && alternate != 1uL
        )
            return null
        val size = input.u32(12).toULong()
        if (size < 92uL || size > header.size.toULong()) return null
        val crc = input.u32(16)
        header.fill(0, 16, 20)
        if (Crc32().apply { update(header, size.toInt()) }.value != crc) return null
        val first = input.u64(40)
        val last = input.u64(48)
        val table = input.u64(72)
        val count = input.u32(80).toULong()
        val stride = input.u32(84).toULong()
        if (
            first < 2uL ||
                first > last ||
                last >= device.geometry.blockCount - 1uL ||
                (location == 1uL && alternate <= last) ||
                count == 0uL ||
                count > Int.MAX_VALUE.toULong() ||
                stride < 128uL ||
                stride % 128uL != 0uL ||
                (stride / 128uL).countOneBits() != 1 ||
                stride > Int.MAX_VALUE.toULong()
        )
            return null
        val length = count * stride
        val blocks = (length - 1uL) / device.geometry.blockSize.toUInt() + 1uL
        if (
            table < 2uL ||
                blocks > device.geometry.blockCount - table ||
                table >= device.geometry.blockCount ||
                !(table + blocks <= first ||
                    table > last && table + blocks <= device.geometry.blockCount - 1uL)
        )
            return null
        val checksum = Crc32()
        val partitions = mutableListOf<Partition>()
        val ids = mutableSetOf<Uuid>()
        var position = table * device.geometry.blockSize.toUInt()
        val entriesPerBatch = maxOf(1, device.preferredTransferBytes / stride.toInt())
        var batch = ByteArray(0)
        repeat(count.toInt()) { index ->
            val within = index % entriesPerBatch
            if (within == 0) {
                val entries = minOf(entriesPerBatch, count.toInt() - index)
                batch = ByteArray(entries * stride.toInt())
                val result =
                    bytes.transfer(
                        BlockOperation.READ,
                        position,
                        ByteArrayBuffer(batch),
                        0,
                        batch.size,
                    )
                if (!result.successful || result.bytes != batch.size) return null
                position += batch.size.toUInt()
                checksum.update(batch, batch.size)
            }
            val startOffset = within * stride.toInt()
            val fields = Fields(batch.copyOfRange(startOffset, startOffset + 128))
            val type = fields.uuid(0)
            if (type == Uuid.NIL) return@repeat
            val id = fields.uuid(16)
            val start = fields.u64(32)
            val end = fields.u64(40)
            if (id == Uuid.NIL || !ids.add(id) || start < first || start > end || end > last)
                return null
            val partition = Partition(
                index + 1,
                id,
                type,
                start,
                end - start + 1uL,
                fields.u64(48) and (1uL shl 60) != 0uL,
            )
            partitions.add(partition)
        }
        if (checksum.value != input.u32(88)) return null
        val ordered = partitions.sortedBy { it.firstBlock }
        if (
            ordered.zipWithNext().any { (left, right) ->
                left.firstBlock + left.blockCount > right.firstBlock
            }
        )
            return null
        return partitions
    }

    private suspend fun read(block: ULong, length: Int): ByteArray? {
        val data = ByteArray(length)
        val result =
            bytes.transfer(
                BlockOperation.READ,
                block * device.geometry.blockSize.toUInt(),
                ByteArrayBuffer(data),
                0,
                length,
            )
        return data.takeIf { result.successful && result.bytes == length }
    }

    private class Fields(private val bytes: ByteArray) {
        fun u32(offset: Int): UInt =
            (0..3).fold(0u) { value, index ->
                value or ((bytes[offset + index].toUInt() and 255u) shl (index * 8))
            }

        fun u64(offset: Int): ULong = u32(offset).toULong() or (u32(offset + 4).toULong() shl 32)

        fun uuid(offset: Int): Uuid {
            val value = bytes.copyOfRange(offset, offset + 16)
            value.reverse(0, 4)
            value.reverse(4, 6)
            value.reverse(6, 8)
            return Uuid.fromByteArray(value)
        }
    }

    private class Crc32 {
        private var crc = UInt.MAX_VALUE
        val value: UInt
            get() = crc.inv()

        fun update(bytes: ByteArray, length: Int) {
            for (index in 0 until length) {
                crc = crc xor (bytes[index].toUInt() and 255u)
                repeat(8) { crc = (crc shr 1) xor (0xedb88320u and (0u - (crc and 1u))) }
            }
        }
    }
}
