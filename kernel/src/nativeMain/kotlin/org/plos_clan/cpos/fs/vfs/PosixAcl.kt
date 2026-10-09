package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class PosixAcl private constructor(private val entries: List<Entry>) {
    private enum class Tag(val value: Int) {
        OWNER(1), USER(2), GROUP_OWNER(4), GROUP(8), MASK(16), OTHER(32),
    }

    private data class Entry(val tag: Tag, val permissions: UInt, val id: UInt)

    private val mask = entries.firstOrNull { it.tag == Tag.MASK }?.permissions ?: 7u

    fun permits(caller: VfsOperationContext, metadata: InodeMetadata, requested: AccessPermissions): Boolean {
        if (caller.uid == metadata.uid) return entries.first().permissions and requested.bits == requested.bits
        val user = entries.firstOrNull { it.tag == Tag.USER && it.id == caller.uid }
        if (user != null) return user.permissions and mask and requested.bits == requested.bits
        var matchedGroup = false
        for (entry in entries) {
            val group = when (entry.tag) {
                Tag.GROUP_OWNER -> metadata.gid
                Tag.GROUP -> entry.id
                else -> continue
            }
            if (!caller.belongsToGroup(group)) continue
            matchedGroup = true
            if (entry.permissions and mask and requested.bits == requested.bits) return true
        }
        return !matchedGroup && entries.last().permissions and requested.bits == requested.bits
    }

    fun mode(previous: FileMode): FileMode {
        val owner = entries.first().permissions shl 6
        val groupBits = entries.firstOrNull { it.tag == Tag.MASK }?.permissions
            ?: entries.first { it.tag == Tag.GROUP_OWNER }.permissions
        val bits = previous.bits and 0x1ffu.inv() or owner or (groupBits shl 3) or entries.last().permissions
        return FileMode(bits)
    }

    fun chmod(mode: FileMode): PosixAcl {
        val hasMask = entries.any { it.tag == Tag.MASK }
        val updated = entries.map { entry ->
            val shift = when (entry.tag) {
                Tag.OWNER -> 6
                Tag.MASK -> 3
                Tag.GROUP_OWNER -> if (hasMask) return@map entry else 3
                Tag.OTHER -> 0
                else -> return@map entry
            }
            entry.copy(permissions = mode.bits shr shift and 7u)
        }
        return PosixAcl(updated)
    }

    fun bytes(): ByteArray {
        val bytes = ByteArray(4 + entries.size * 8)
        val data = LittleEndianBuffer(bytes)
        data.writeU32(0, 2u)
        entries.forEachIndexed { index, entry ->
            val offset = 4 + index * 8
            data.writeU16(offset, entry.tag.value.toUShort())
            data.writeU16(offset + 2, entry.permissions.toUShort())
            data.writeU32(offset + 4, entry.id)
        }
        return bytes
    }

    companion object {
        val ACCESS = (ExtendedAttributeName.fromBytes("system.posix_acl_access".encodeToByteArray()) as VfsResult.Ok).value
        val DEFAULT = (ExtendedAttributeName.fromBytes("system.posix_acl_default".encodeToByteArray()) as VfsResult.Ok).value

        fun parse(bytes: ByteArray): PosixAcl? {
            if (bytes.size < 28 || (bytes.size - 4) % 8 != 0) return null
            val data = LittleEndianBuffer(bytes)
            if (data.readU32(0) != 2u) return null
            val entries = ArrayList<Entry>((bytes.size - 4) / 8)
            var previous: Entry? = null
            for (offset in 4 until bytes.size step 8) {
                val tag = Tag.entries.firstOrNull { it.value == data.readU16(offset).toInt() } ?: return null
                val permissions = data.readU16(offset + 2).toUInt()
                val id = data.readU32(offset + 4)
                if (permissions > 7u) return null
                val named = tag == Tag.USER || tag == Tag.GROUP
                if (named == (id == UInt.MAX_VALUE)) return null
                val prior = previous
                if (prior != null && (tag.value < prior.tag.value ||
                        tag == prior.tag && (!named || id <= prior.id))) return null
                val entry = Entry(tag, permissions, id)
                entries.add(entry)
                previous = entry
            }
            if (entries.first().tag != Tag.OWNER || entries.last().tag != Tag.OTHER) return null
            if (entries.none { it.tag == Tag.GROUP_OWNER }) return null
            val named = entries.any { it.tag == Tag.USER || it.tag == Tag.GROUP }
            if (named && entries.none { it.tag == Tag.MASK }) return null
            return PosixAcl(entries)
        }
    }
}
