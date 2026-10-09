package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal enum class DrmCommand(val number: Int, val size: Int, val direction: Int = 3) {
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
    SET_CRTC(0xa2, 104),
    CURSOR(0xa3, 28),
    GET_GAMMA(0xa4, 32),
    SET_GAMMA(0xa5, 32),
    GET_ENCODER(0xa6, 20),
    GET_CONNECTOR(0xa7, 80),
    GET_PROPERTY(0xaa, 64),
    SET_CONNECTOR_PROPERTY(0xab, 16),
    GET_FRAMEBUFFER(0xad, 28),
    ADD_FRAMEBUFFER(0xae, 28),
    REMOVE_FRAMEBUFFER(0xaf, 4),
    PAGE_FLIP(0xb0, 24),
    DIRTY_FRAMEBUFFER(0xb1, 24),
    CREATE_DUMB(0xb2, 32),
    MAP_DUMB(0xb3, 16),
    DESTROY_DUMB(0xb4, 4),
    GET_PLANE_RESOURCES(0xb5, 16),
    GET_PLANE(0xb6, 32),
    ADD_FRAMEBUFFER2(0xb8, 104),
    GET_OBJECT_PROPERTIES(0xb9, 32),
    SET_OBJECT_PROPERTY(0xba, 24),
    CURSOR2(0xbb, 36),
    CREATE_LEASE(0xc6, 24),
    CLOSE_FRAMEBUFFER(0xd0, 8);

    val ioctl = (direction shl 30) or (size shl 16) or (0x64 shl 8) or number

    companion object {
        private val byNumber = entries.associateBy { it.number }

        fun from(command: Int): DrmCommand? = byNumber[command and 0xff]?.takeIf { it.ioctl == command }
    }
}

internal class DrmArgument(val bytes: ByteArray, private val destination: UserMemory) {
    val data = LittleEndianBuffer(bytes)

    fun write(): Boolean = destination.copyToUser(bytes)

    fun copy(pointer: ULong, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val process = ProcessManager.currentProcess() ?: return false
        val memory = UserMemory(process.addressSpace, pointer)
        return memory.copyToUser(bytes)
    }

    fun array(pointerOffset: Int, countOffset: Int, values: List<UInt>): Boolean {
        val capacity = data.readU32(countOffset)
        data.writeU32(countOffset, values.size.toUInt())
        if (capacity < values.size.toUInt()) return true
        val bytes = ByteArray(values.size * UInt.SIZE_BYTES)
        val output = LittleEndianBuffer(bytes)
        values.forEachIndexed { index, value -> output.writeU32(index * UInt.SIZE_BYTES, value) }
        return copy(data.readU64(pointerOffset), bytes)
    }

    fun properties(
        pointerOffset: Int,
        countOffset: Int,
        properties: List<Pair<DrmProperty, ULong>>,
    ): Boolean {
        val capacity = data.readU32(countOffset)
        val ids = properties.map { it.first.id }
        if (!array(pointerOffset, countOffset, ids)) return false
        if (capacity < properties.size.toUInt() || properties.isEmpty()) return true
        val values = ByteArray(properties.size * 8)
        val output = LittleEndianBuffer(values)
        properties.forEachIndexed { index, property -> output.writeU64(index * 8, property.second) }
        return copy(data.readU64(pointerOffset + 8), values)
    }

    fun string(lengthOffset: Int, value: String): Boolean {
        val bytes = value.encodeToByteArray()
        val capacity = data.readU64(lengthOffset)
        data.writeU64(lengthOffset, bytes.size.toULong())
        val count = minOf(capacity, bytes.size.toULong()).toInt()
        return copy(data.readU64(lengthOffset + 8), bytes.copyOf(count))
    }
}
