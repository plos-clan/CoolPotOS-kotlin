package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal enum class DrmCommand(
    val number: Int,
    val size: Int,
    val direction: Int = 3,
    val waitForFlip: Boolean = false,
) {
    VERSION(0x00, 64),
    GET_UNIQUE(0x01, 16),
    GET_MAGIC(0x02, 4, 2),
    GEM_CLOSE(0x09, 8, 1),
    GET_CAP(0x0c, 16),
    SET_CLIENT_CAP(0x0d, 16, 1),
    AUTH_MAGIC(0x11, 4, 1),
    SET_MASTER(0x1e, 0, 0),
    DROP_MASTER(0x1f, 0, 0),
    PRIME_HANDLE_TO_FD(0x2d, 12),
    PRIME_FD_TO_HANDLE(0x2e, 12),
    GET_RESOURCES(0xa0, 64),
    GET_CRTC(0xa1, 104),
    SET_CRTC(0xa2, 104, waitForFlip = true),
    CURSOR(0xa3, 28),
    GET_GAMMA(0xa4, 32),
    SET_GAMMA(0xa5, 32),
    GET_ENCODER(0xa6, 20),
    GET_CONNECTOR(0xa7, 80),
    GET_PROPERTY(0xaa, 64),
    SET_CONNECTOR_PROPERTY(0xab, 16, waitForFlip = true),
    GET_FRAMEBUFFER(0xad, 28),
    ADD_FRAMEBUFFER(0xae, 28),
    REMOVE_FRAMEBUFFER(0xaf, 4, waitForFlip = true),
    PAGE_FLIP(0xb0, 24),
    DIRTY_FRAMEBUFFER(0xb1, 24),
    CREATE_DUMB(0xb2, 32),
    MAP_DUMB(0xb3, 16),
    DESTROY_DUMB(0xb4, 4),
    GET_PLANE_RESOURCES(0xb5, 16),
    GET_PLANE(0xb6, 32),
    ADD_FRAMEBUFFER2(0xb8, 104),
    GET_OBJECT_PROPERTIES(0xb9, 32),
    SET_OBJECT_PROPERTY(0xba, 24, waitForFlip = true),
    CURSOR2(0xbb, 36),
    CREATE_LEASE(0xc6, 24),
    CLOSE_FRAMEBUFFER(0xd0, 8);

    val ioctl = (direction shl 30) or (size shl 16) or (0x64 shl 8) or number

    companion object {
        private val byNumber = entries.associateBy { it.number }

        fun from(command: Int): DrmCommand? = byNumber[command and 0xff]?.takeIf { it.ioctl == command }
    }
}

internal class DrmArgument(
    val bytes: ByteArray,
    private val destination: UserMemory,
    val size: Int,
) {
    val data = LittleEndianBuffer(bytes)

    fun write(): Boolean = destination.writeFrom(bytes, size)

    fun copy(pointer: ULong, bytes: ByteArray, count: Int = bytes.size): Boolean =
        destination.at(pointer).copyToUser(bytes, size = count)

    fun array(pointerOffset: Int, countOffset: Int, item: UInt?): Boolean {
        val capacity = data.readU32(countOffset)
        data.writeU32(countOffset, if (item == null) 0u else 1u)
        if (item == null || capacity == 0u) return true
        return destination.at(data.readU64(pointerOffset)).writeUInt(item)
    }

    inline fun <T> array(
        pointerOffset: Int,
        countOffset: Int,
        values: Collection<T>,
        id: (T) -> UInt,
    ): Boolean {
        val capacity = data.readU32(countOffset)
        data.writeU32(countOffset, values.size.toUInt())
        if (capacity < values.size.toUInt() || values.isEmpty()) return true
        val output = destination.at(data.readU64(pointerOffset))
        return output.writeRecord(values.size * UInt.SIZE_BYTES) { record ->
            values.forEachIndexed { index, value -> record.writeU32(index * UInt.SIZE_BYTES, id(value)) }
        }
    }

    fun property(pointerOffset: Int, countOffset: Int, property: DrmProperty?, value: ULong = 0uL): Boolean {
        val capacity = data.readU32(countOffset)
        if (!array(pointerOffset, countOffset, property?.id)) return false
        if (capacity == 0u || property == null) return true
        return destination.at(data.readU64(pointerOffset + ULong.SIZE_BYTES)).writeULong(value)
    }

    fun string(lengthOffset: Int, bytes: ByteArray): Boolean {
        val capacity = data.readU64(lengthOffset)
        data.writeU64(lengthOffset, bytes.size.toULong())
        val count = minOf(capacity, bytes.size.toULong()).toInt()
        return copy(data.readU64(lengthOffset + 8), bytes, count)
    }
}
