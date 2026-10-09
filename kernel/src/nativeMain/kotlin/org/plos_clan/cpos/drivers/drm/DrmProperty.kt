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

internal enum class DrmProperty(
    val id: UInt,
    private val title: String,
    private val flags: UInt,
    private val labels: List<String>,
) {
    TYPE(5u, "type", 0xcu, listOf("Overlay", "Primary", "Cursor")),
    DPMS(6u, "DPMS", 0x8u, DrmPower.entries.map { it.label });

    fun query(argument: DrmArgument): Boolean {
        val data = argument.data
        val valueCapacity = data.readU32(56)
        val enumCapacity = data.readU32(60)
        val count = labels.size.toUInt()
        data.writeU32(20, flags)
        argument.bytes.fill(0, 24, 56)
        title.encodeToByteArray().copyInto(argument.bytes, 24)
        data.writeU32(56, count)
        data.writeU32(60, count)
        if (valueCapacity >= count) {
            val values = ByteArray(labels.size * 8)
            val output = LittleEndianBuffer(values)
            labels.indices.forEach { output.writeU64(it * 8, it.toULong()) }
            if (!argument.copy(data.readU64(0), values)) return false
        }
        if (enumCapacity < count) return true
        val values = ByteArray(labels.size * 40)
        val output = LittleEndianBuffer(values)
        labels.forEachIndexed { index, name ->
            output.writeU64(index * 40, index.toULong())
            name.encodeToByteArray().copyInto(values, index * 40 + 8)
        }
        return argument.copy(data.readU64(8), values)
    }
}
