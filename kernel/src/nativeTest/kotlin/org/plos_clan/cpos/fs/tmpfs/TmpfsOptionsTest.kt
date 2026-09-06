package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TmpfsOptionsTest {
    @Test
    fun acceptsSystemdRuntimeMountOptions() {
        val options = assertIs<VfsResult.Ok<TmpfsOptions>>(TmpfsOptions.parse(
            "mode=0755,size=20%,nr_inodes=800k".encodeToByteArray(),
            2uL shl 30,
        )).value

        assertEquals(FileMode(0x1EDu), options.rootMode)
        assertEquals(429_496_729uL, options.sizeLimit)
        assertEquals(819_200uL, options.inodeLimit)
    }

    @Test
    fun acceptsCountsAndBinarySuffixesForBothLimits() {
        val values = mapOf(
            "0" to 0uL,
            "1" to 1uL,
            "800k" to 819_200uL,
            "2K" to 2_048uL,
            "3m" to (3uL shl 20),
            "4G" to (4uL shl 30),
            "5t" to (5uL shl 40),
            "6P" to (6uL shl 50),
            "15E" to (15uL shl 60),
            "010" to 8uL,
            "0x10k" to 16_384uL,
            ULong.MAX_VALUE.toString() to ULong.MAX_VALUE,
        )
        for ((value, expected) in values) {
            val options = assertIs<VfsResult.Ok<TmpfsOptions>>(TmpfsOptions.parse(
                "size=$value,nr_inodes=$value".encodeToByteArray(),
                0uL,
            )).value
            assertEquals(expected, options.sizeLimit, value)
            assertEquals(expected, options.inodeLimit, value)
        }
    }

    @Test
    fun rejectsInvalidAndOverflowingInodeLimits() {
        for (value in listOf(
            "", "-1", "+1", "1.5k", "800kb", "1kB", "20%", "k", "08", "0x", "0x+1",
            "18446744073709551616", "18014398509481984k", "16e",
        )) {
            val result = TmpfsOptions.parse("nr_inodes=$value".encodeToByteArray(), 0uL)
            assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), result, value)
        }
    }

    @Test
    fun usesLastLimitAndPreservesOwnershipOptions() {
        val options = assertIs<VfsResult.Ok<TmpfsOptions>>(TmpfsOptions.parse(
            "nr_inodes=1,nr_inodes=0,mode=1777,uid=1000,gid=100".encodeToByteArray(),
            0uL,
        )).value
        assertEquals(0uL, options.inodeLimit)
        assertEquals(FileMode(0x3FFu), options.rootMode)
        assertEquals(1000u, options.rootUid)
        assertEquals(100u, options.rootGid)
        assertEquals(VfsResult.Ok(TmpfsOptions()), TmpfsOptions.parse(null, 0uL))
        assertEquals(VfsResult.Ok(TmpfsOptions()), TmpfsOptions.parse(ByteArray(0), 0uL))
    }

    @Test
    fun calculatesMemoryPercentagesWithoutOverflow() {
        for (percentage in listOf(0uL, 20uL, 100uL)) {
            val options = assertIs<VfsResult.Ok<TmpfsOptions>>(TmpfsOptions.parse(
                "size=$percentage%".encodeToByteArray(),
                ULong.MAX_VALUE,
            )).value
            val expected = ULong.MAX_VALUE / 100uL * percentage +
                ULong.MAX_VALUE % 100uL * percentage / 100uL
            assertEquals(expected, options.sizeLimit)
        }
        assertEquals(
            VfsResult.Err(VfsError.INVALID_ARGUMENT),
            TmpfsOptions.parse("size=101%".encodeToByteArray(), 0uL),
        )
    }
}
