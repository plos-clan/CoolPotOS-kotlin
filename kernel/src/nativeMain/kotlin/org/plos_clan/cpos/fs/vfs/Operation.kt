package org.plos_clan.cpos.fs.vfs

data class VfsOperationContext(
    val uid: UInt,
    val gid: UInt,
    val processId: UInt,
    val supplementaryGroups: List<Int> = emptyList(),
    val fileCreationMask: UInt = 0u,
    val privileged: Boolean = uid == 0u,
) {
    fun belongsToGroup(group: UInt): Boolean = group == gid ||
        group <= Int.MAX_VALUE.toUInt() && group.toInt() in supplementaryGroups

    companion object {
        val KERNEL = VfsOperationContext(0u, 0u, 0u, privileged = true)
    }
}

enum class AccessPermission(internal val bit: UInt) {
    EXECUTE(1u),
    WRITE(2u),
    READ(4u),
}

value class AccessPermissions private constructor(internal val bits: UInt) {
    operator fun contains(permission: AccessPermission): Boolean =
        bits and permission.bit != 0u

    operator fun plus(permission: AccessPermission): AccessPermissions =
        AccessPermissions(bits or permission.bit)

    companion object {
        val NONE = AccessPermissions(0u)
        val EXECUTE = NONE + AccessPermission.EXECUTE
        val WRITE = NONE + AccessPermission.WRITE
        val READ = NONE + AccessPermission.READ
        val WRITE_AND_EXECUTE = WRITE + AccessPermission.EXECUTE

        fun fromBits(bits: UInt): AccessPermissions? =
            bits.takeIf { it and 0x7u.inv() == 0u }?.let(::AccessPermissions)
    }
}
