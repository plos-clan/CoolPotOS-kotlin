package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE

/** Inode-wide policy; the file's content lock serializes all operations on this state. */
internal class FileSeals(initial: Int = SEAL) {
    var bits: Int = initial
        private set
    private var writableMappings = 0

    fun add(requested: Int, mode: FileMode): VfsResult<Unit> {
        if (requested and ALL.inv() != 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (bits and SEAL != 0) return VfsResult.Err(VfsError.NOT_PERMITTED)
        val added = if (requested and EXEC != 0 && mode.bits and EXECUTE_BITS != 0u) {
            requested or SHRINK or GROW or WRITE or FUTURE_WRITE
        } else requested
        if (added and WRITE != 0 && writableMappings != 0) return VfsResult.Err(VfsError.BUSY)
        bits = bits or added
        return VfsResult.Ok(Unit)
    }

    fun acquireMapping(shared: Boolean, access: ULong, maximumAccess: ULong): VfsResult<ULong> {
        if (!shared) return VfsResult.Ok(maximumAccess)
        val maximum = if (bits and (WRITE or FUTURE_WRITE) != 0) {
            if (access and MEMORY_REGION_WRITABLE != 0uL) {
                return VfsResult.Err(VfsError.NOT_PERMITTED)
            }
            maximumAccess and MEMORY_REGION_WRITABLE.inv()
        } else maximumAccess
        if (maximum and MEMORY_REGION_WRITABLE != 0uL) writableMappings++
        return VfsResult.Ok(maximum)
    }

    fun releaseMapping(shared: Boolean, maximumAccess: ULong) {
        if (shared && maximumAccess and MEMORY_REGION_WRITABLE != 0uL) {
            check(writableMappings > 0)
            writableMappings--
        }
    }

    fun allowsResize(previous: ULong, size: ULong): Boolean =
        !(size < previous && bits and SHRINK != 0 || size > previous && bits and GROW != 0)

    fun allowsWrite(size: ULong, end: ULong): Boolean =
        bits and (WRITE or FUTURE_WRITE) == 0 && (end <= size || bits and GROW == 0)

    fun allowsMode(previous: FileMode, mode: FileMode): Boolean =
        bits and EXEC == 0 || (previous.bits xor mode.bits) and EXECUTE_BITS == 0u

    companion object {
        const val SEAL = 0x01
        const val SHRINK = 0x02
        const val GROW = 0x04
        const val WRITE = 0x08
        const val FUTURE_WRITE = 0x10
        const val EXEC = 0x20
        const val ALL = SEAL or SHRINK or GROW or WRITE or FUTURE_WRITE or EXEC
        const val EXECUTE_BITS = 0x49u
    }
}

internal interface SealableFile {
    fun getSeals(): Int
    fun addSeals(inode: Inode, seals: Int): VfsResult<Unit>
}
