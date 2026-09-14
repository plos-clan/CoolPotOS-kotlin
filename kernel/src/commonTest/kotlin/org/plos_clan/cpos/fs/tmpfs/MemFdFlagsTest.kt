package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.FileSeals
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemFdFlagsTest {
    @Test
    fun creationFlagsSelectLinuxDefaults() {
        for (bits in 0u..31u) {
            if (bits and MemFdFlags.HUGETLB != 0u || bits and 0x18u == 0x18u) continue
            val flags = assertIs<VfsResult.Ok<MemFdFlags>>(MemFdFlags.from(bits)).value
            val noexec = bits and MemFdFlags.NOEXEC_SEAL != 0u
            assertEquals(bits and MemFdFlags.CLOEXEC != 0u, flags.closeOnExec)
            assertEquals(!noexec, flags.executable)
            assertEquals(
                when {
                    noexec -> FileSeals.EXEC
                    bits and MemFdFlags.ALLOW_SEALING != 0u -> 0
                    else -> FileSeals.SEAL
                },
                flags.initialSeals,
            )
        }
    }

    @Test
    fun invalidFlagsAreDistinctFromUnsupportedHugePages() {
        for (bits in listOf(0x18u, 0x20u, 0x8000_0000u, UInt.MAX_VALUE)) {
            assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), MemFdFlags.from(bits))
        }
        for (bits in listOf(4u, 6u, 4u or (21u shl 26))) {
            assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), MemFdFlags.from(bits))
        }
    }
}
