package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal enum class DrmPower(val label: String) {
    ON("On"),
    STANDBY("Standby"),
    SUSPEND("Suspend"),
    OFF("Off");

    val value: ULong get() = ordinal.toULong()

    companion object {
        fun from(value: ULong): DrmPower? = entries.firstOrNull { it.value == value }
    }
}

private const val ENUM_SIZE = 40

internal enum class DrmProperty(
    val id: UInt,
    title: String,
    private val flags: UInt,
    labels: List<String>,
) {
    TYPE(5u, "type", 0xcu, listOf("Overlay", "Primary", "Cursor")),
    DPMS(6u, "DPMS", 0x8u, DrmPower.entries.map { it.label });

    private val nameBytes = title.encodeToByteArray()
    private val values = ByteArray(labels.size * ULong.SIZE_BYTES)
    private val enums = ByteArray(labels.size * ENUM_SIZE)

    init {
        val numbers = LittleEndianBuffer(values)
        val entries = LittleEndianBuffer(enums)
        labels.forEachIndexed { index, name ->
            numbers.writeU64(index * ULong.SIZE_BYTES, index.toULong())
            val offset = index * ENUM_SIZE
            entries.writeU64(offset, index.toULong())
            name.encodeToByteArray().copyInto(enums, offset + ULong.SIZE_BYTES)
        }
    }

    fun query(argument: DrmArgument): Boolean {
        val data = argument.data
        val valueCapacity = data.readU32(56)
        val enumCapacity = data.readU32(60)
        val count = (values.size / ULong.SIZE_BYTES).toUInt()
        data.writeU32(20, flags)
        argument.bytes.fill(0, 24, 56)
        nameBytes.copyInto(argument.bytes, 24)
        data.writeU32(56, count)
        data.writeU32(60, count)
        if (valueCapacity >= count && !argument.copy(data.readU64(0), values)) return false
        return enumCapacity < count || argument.copy(data.readU64(8), enums)
    }
}
