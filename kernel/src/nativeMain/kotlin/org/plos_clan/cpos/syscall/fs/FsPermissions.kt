package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.tasks.Process

internal object FsPermissions {
    fun Process.mayWrite(metadata: InodeMetadata): Boolean {
        if (euid == 0) return true
        val shift = when {
            fsuid.toUInt() == metadata.uid -> 6
            fsgid.toUInt() == metadata.gid -> 3
            else -> 0
        }
        return metadata.mode.bits shr shift and 0x2u != 0u
    }
}
