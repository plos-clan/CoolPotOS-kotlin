package org.plos_clan.cpos.fs.devpts

import org.plos_clan.cpos.fs.vfs.FileSystemParameters
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DevptsOptionsTest {
    @Test
    fun parsesSystemdMountOptions() {
        val result = DevptsOptions.parse(FileSystemParameters.fromMountData("gid=5,mode=620,ptmxmode=666,newinstance,max=128".encodeToByteArray()))
        val options = assertIs<VfsResult.Ok<DevptsOptions>>(result).value
        assertEquals(5u, options.gid)
        assertEquals(0x190u, options.mode)
        assertEquals(0x1B6u, options.ptmxMode)
        assertEquals(128, options.maximum)
    }

    @Test
    fun rejectsInvalidLimitsAndUnknownOptions() {
        for (value in listOf("max=-1", "max=4294967295", "gid=4294967295", "mode=888", "unknown=1")) {
            assertIs<VfsResult.Err>(DevptsOptions.parse(FileSystemParameters.fromMountData(value.encodeToByteArray())))
        }
    }
}
