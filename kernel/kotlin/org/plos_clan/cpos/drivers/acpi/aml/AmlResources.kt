package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.utils.LittleEndianBuffer

sealed class AcpiResource

data class AcpiIrqResource(
    val interrupts: List<UInt>,
    val levelTriggered: Boolean,
    val activeLow: Boolean,
    val shared: Boolean,
    val wakeCapable: Boolean = false,
) : AcpiResource()

data class AcpiIoResource(
    val minimum: UInt,
    val maximum: UInt,
    val alignment: UInt,
    val length: UInt,
    val decode16: Boolean,
) : AcpiResource()

data class AcpiMemoryResource(
    val minimum: ULong,
    val maximum: ULong,
    val length: ULong,
    val writable: Boolean,
    val fixed: Boolean,
) : AcpiResource()

data class AcpiAddressSpaceResource(
    val resourceType: UInt,
    val minimum: ULong,
    val maximum: ULong,
    val translationOffset: ULong,
    val length: ULong,
    val flags: UInt,
    val typeFlags: UInt,
) : AcpiResource()

data class AcpiRegisterResource(
    val addressSpaceId: UInt,
    val bitWidth: UInt,
    val bitOffset: UInt,
    val accessSize: UInt,
    val address: ULong,
) : AcpiResource()

data class AcpiVendorResource(val data: ByteArray) : AcpiResource() {
    override fun equals(other: Any?): Boolean =
        other is AcpiVendorResource && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()
}

object AmlResourceTemplateParser {
    fun parse(buffer: AmlBuffer): List<AcpiResource>? = parse(buffer.bytes)

    fun parse(bytes: ByteArray): List<AcpiResource>? {
        val reader = AmlByteReader(AmlArraySource(bytes))
        val resources = mutableListOf<AcpiResource>()

        while (reader.remaining > 0) {
            val header = reader.readU8() ?: return null
            if ((header and 0x80u) == 0u) {
                val type = (header shr 3) and 0x0Fu
                val length = (header and 0x07u).toInt()
                val payload = reader.readBytes(length) ?: return null
                if (type == 0x0Fu) {
                    return resources
                }
                parseSmall(type, payload)?.let(resources::add)
            } else {
                val type = header and 0x7Fu
                val length = reader.readU16()?.toInt() ?: return null
                val payload = reader.readBytes(length) ?: return null
                parseLarge(type, payload)?.let(resources::add)
            }
        }
        return resources
    }

    private fun parseSmall(type: UInt, payload: ByteArray): AcpiResource? = when (type) {
        0x04u -> parseIrq(payload)
        0x08u -> if (payload.size >= 7) {
            val input = LittleEndianBuffer(payload)
            AcpiIoResource(
                minimum = input.readU16(1).toUInt(),
                maximum = input.readU16(3).toUInt(),
                alignment = payload[5].toUByte().toUInt(),
                length = payload[6].toUByte().toUInt(),
                decode16 = (payload[0].toUByte().toUInt() and 1u) != 0u,
            )
        } else null
        0x09u -> if (payload.size >= 3) {
            val base = LittleEndianBuffer(payload).readU16(0).toUInt()
            AcpiIoResource(
                minimum = base,
                maximum = base,
                alignment = 1u,
                length = payload[2].toUByte().toUInt(),
                decode16 = true,
            )
        } else null
        0x0Eu -> AcpiVendorResource(payload)
        else -> null
    }

    private fun parseLarge(type: UInt, payload: ByteArray): AcpiResource? = when (type) {
        0x01u -> parseMemory24(payload)
        0x02u -> parseRegister(payload)
        0x04u -> AcpiVendorResource(payload)
        0x05u -> parseMemory32(payload)
        0x06u -> parseFixedMemory32(payload)
        0x07u -> parseAddress(payload, 4)
        0x08u -> parseAddress(payload, 2)
        0x09u -> parseExtendedIrq(payload)
        0x0Au -> parseAddress(payload, 8)
        else -> null
    }

    private fun parseIrq(payload: ByteArray): AcpiResource? {
        if (payload.size !in 2..3) return null
        val mask = LittleEndianBuffer(payload).readU16(0).toInt()
        val flags = payload.getOrNull(2)?.toUByte()?.toUInt() ?: 0u
        return AcpiIrqResource(
            interrupts = (0 until 16).filter { (mask and (1 shl it)) != 0 }.map(Int::toUInt),
            levelTriggered = (flags and 0x01u) != 0u,
            activeLow = (flags and 0x08u) != 0u,
            shared = (flags and 0x10u) != 0u,
        )
    }

    private fun parseExtendedIrq(payload: ByteArray): AcpiResource? {
        if (payload.size < 2) return null
        val flags = payload[0].toUByte().toUInt()
        val count = payload[1].toUByte().toInt()
        if (count > (payload.size - 2) / 4) return null
        val input = LittleEndianBuffer(payload)
        return AcpiIrqResource(
            interrupts = List(count) { input.readU32(2 + it * 4) },
            levelTriggered = (flags and 0x02u) != 0u,
            activeLow = (flags and 0x04u) != 0u,
            shared = (flags and 0x08u) != 0u,
            wakeCapable = (flags and 0x10u) != 0u,
        )
    }

    private fun parseMemory24(payload: ByteArray): AcpiResource? =
        if (payload.size >= 9) {
            val input = LittleEndianBuffer(payload)
            AcpiMemoryResource(
                minimum = (input.readU16(1).toULong() shl 8),
                maximum = (input.readU16(3).toULong() shl 8),
                length = (input.readU16(7).toULong() shl 8),
                writable = (payload[0].toUByte().toUInt() and 1u) != 0u,
                fixed = false,
            )
        } else null

    private fun parseMemory32(payload: ByteArray): AcpiResource? =
        if (payload.size >= 17) {
            val input = LittleEndianBuffer(payload)
            AcpiMemoryResource(
                minimum = input.readU32(1).toULong(),
                maximum = input.readU32(5).toULong(),
                length = input.readU32(13).toULong(),
                writable = (payload[0].toUByte().toUInt() and 1u) != 0u,
                fixed = false,
            )
        } else null

    private fun parseFixedMemory32(payload: ByteArray): AcpiResource? =
        if (payload.size >= 9) {
            val input = LittleEndianBuffer(payload)
            val base = input.readU32(1).toULong()
            AcpiMemoryResource(
                minimum = base,
                maximum = base,
                length = input.readU32(5).toULong(),
                writable = (payload[0].toUByte().toUInt() and 1u) != 0u,
                fixed = true,
            )
        } else null

    private fun parseRegister(payload: ByteArray): AcpiResource? =
        if (payload.size >= 12) {
            AcpiRegisterResource(
                addressSpaceId = payload[0].toUByte().toUInt(),
                bitWidth = payload[1].toUByte().toUInt(),
                bitOffset = payload[2].toUByte().toUInt(),
                accessSize = payload[3].toUByte().toUInt(),
                address = LittleEndianBuffer(payload).readU64(4),
            )
        } else null

    private fun parseAddress(payload: ByteArray, width: Int): AcpiResource? {
        val required = 3 + width * 5
        if (payload.size < required) return null
        val input = LittleEndianBuffer(payload)
        fun value(index: Int): ULong = input.readUnsigned(3 + index * width, width)
        return AcpiAddressSpaceResource(
            resourceType = payload[0].toUByte().toUInt(),
            flags = payload[1].toUByte().toUInt(),
            typeFlags = payload[2].toUByte().toUInt(),
            minimum = value(1),
            maximum = value(2),
            translationOffset = value(3),
            length = value(4),
        )
    }
}
