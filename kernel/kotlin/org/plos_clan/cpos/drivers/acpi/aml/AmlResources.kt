package org.plos_clan.cpos.drivers.acpi.aml

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
        val reader = ResourceReader(bytes)
        val resources = mutableListOf<AcpiResource>()

        while (reader.remaining > 0) {
            val header = reader.u8() ?: return null
            if ((header and 0x80u) == 0u) {
                val type = (header shr 3) and 0x0Fu
                val length = (header and 0x07u).toInt()
                val payload = reader.bytes(length) ?: return null
                if (type == 0x0Fu) {
                    return resources
                }
                parseSmall(type, payload)?.let(resources::add)
            } else {
                val type = header and 0x7Fu
                val length = reader.u16()?.toInt() ?: return null
                val payload = reader.bytes(length) ?: return null
                parseLarge(type, payload)?.let(resources::add)
            }
        }
        return resources
    }

    private fun parseSmall(type: UInt, payload: ByteArray): AcpiResource? = when (type) {
        0x04u -> parseIrq(payload)
        0x08u -> if (payload.size >= 7) {
            AcpiIoResource(
                minimum = payload.u16(1).toUInt(),
                maximum = payload.u16(3).toUInt(),
                alignment = payload[5].toUByte().toUInt(),
                length = payload[6].toUByte().toUInt(),
                decode16 = (payload[0].toUByte().toUInt() and 1u) != 0u,
            )
        } else null
        0x09u -> if (payload.size >= 3) {
            val base = payload.u16(0).toUInt()
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
        val mask = payload.u16(0)
        val flags = payload.getOrNull(2)?.toUByte()?.toUInt() ?: 0x01u
        return AcpiIrqResource(
            interrupts = (0 until 16).filter { (mask and (1 shl it)) != 0 }.map(Int::toUInt),
            levelTriggered = (flags and 0x01u) == 0u,
            activeLow = (flags and 0x08u) != 0u,
            shared = (flags and 0x10u) != 0u,
        )
    }

    private fun parseExtendedIrq(payload: ByteArray): AcpiResource? {
        if (payload.size < 2) return null
        val flags = payload[0].toUByte().toUInt()
        val count = payload[1].toUByte().toInt()
        if (count > (payload.size - 2) / 4) return null
        return AcpiIrqResource(
            interrupts = List(count) { payload.u32(2 + it * 4) },
            levelTriggered = (flags and 0x02u) == 0u,
            activeLow = (flags and 0x04u) != 0u,
            shared = (flags and 0x08u) != 0u,
            wakeCapable = (flags and 0x10u) != 0u,
        )
    }

    private fun parseMemory24(payload: ByteArray): AcpiResource? =
        if (payload.size >= 9) {
            AcpiMemoryResource(
                minimum = (payload.u16(1).toULong() shl 8),
                maximum = (payload.u16(3).toULong() shl 8),
                length = (payload.u16(7).toULong() shl 8),
                writable = (payload[0].toUByte().toUInt() and 1u) != 0u,
                fixed = false,
            )
        } else null

    private fun parseMemory32(payload: ByteArray): AcpiResource? =
        if (payload.size >= 17) {
            AcpiMemoryResource(
                minimum = payload.u32(1).toULong(),
                maximum = payload.u32(5).toULong(),
                length = payload.u32(13).toULong(),
                writable = (payload[0].toUByte().toUInt() and 1u) != 0u,
                fixed = false,
            )
        } else null

    private fun parseFixedMemory32(payload: ByteArray): AcpiResource? =
        if (payload.size >= 9) {
            val base = payload.u32(1).toULong()
            AcpiMemoryResource(
                minimum = base,
                maximum = base,
                length = payload.u32(5).toULong(),
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
                address = payload.u64(4),
            )
        } else null

    private fun parseAddress(payload: ByteArray, width: Int): AcpiResource? {
        val required = 3 + width * 5
        if (payload.size < required) return null
        fun value(index: Int): ULong = payload.unsigned(3 + index * width, width)
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

private class ResourceReader(private val bytes: ByteArray) {
    private var position = 0
    val remaining: Int
        get() = bytes.size - position

    fun u8(): UInt? = bytes.getOrNull(position++)?.toUByte()?.toUInt()

    fun u16(): UInt? {
        if (remaining < 2) return null
        return bytes.u16(position).toUInt().also { position += 2 }
    }

    fun bytes(count: Int): ByteArray? {
        if (count < 0 || count > remaining) return null
        return bytes.copyOfRange(position, position + count).also { position += count }
    }
}

private fun ByteArray.u16(offset: Int): Int =
    unsigned(offset, 2).toInt()

private fun ByteArray.u32(offset: Int): UInt =
    unsigned(offset, 4).toUInt()

private fun ByteArray.u64(offset: Int): ULong =
    unsigned(offset, 8)

private fun ByteArray.unsigned(offset: Int, width: Int): ULong {
    var value = 0uL
    repeat(width) { index ->
        value = value or (this[offset + index].toUByte().toULong() shl (index * 8))
    }
    return value
}
