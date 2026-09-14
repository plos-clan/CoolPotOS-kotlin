package org.plos_clan.cpos.fs.tmpfs

import kotlin.jvm.JvmInline

import org.plos_clan.cpos.fs.vfs.FileSeals
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult

@JvmInline
internal value class MemFdFlags private constructor(val bits: UInt) {
    val closeOnExec: Boolean
        get() = bits and CLOEXEC != 0u
    val executable: Boolean
        get() = bits and NOEXEC_SEAL == 0u
    val initialSeals: Int
        get() = when {
            !executable -> FileSeals.EXEC
            bits and ALLOW_SEALING != 0u -> 0
            else -> FileSeals.SEAL
        }

    companion object {
        const val CLOEXEC = 0x01u
        const val ALLOW_SEALING = 0x02u
        const val HUGETLB = 0x04u
        const val NOEXEC_SEAL = 0x08u
        const val EXEC = 0x10u
        private const val HUGE_SIZE_MASK = 0xfc00_0000u

        fun from(bits: UInt): VfsResult<MemFdFlags> {
            val huge = bits and HUGETLB != 0u
            val allowed = CLOEXEC or ALLOW_SEALING or HUGETLB or NOEXEC_SEAL or EXEC or
                (if (huge) HUGE_SIZE_MASK else 0u)
            if (bits and allowed.inv() != 0u || bits and (NOEXEC_SEAL or EXEC) == (NOEXEC_SEAL or EXEC)) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (huge) return VfsResult.Err(VfsError.NOT_SUPPORTED)
            return VfsResult.Ok(MemFdFlags(bits))
        }
    }
}
