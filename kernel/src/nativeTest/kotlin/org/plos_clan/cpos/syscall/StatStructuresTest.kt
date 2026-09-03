package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemStatistics
import org.plos_clan.cpos.fs.vfs.InodeId
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.vfs.VfsTimestamp
import org.plos_clan.cpos.syscall.fs.FsConstants
import org.plos_clan.cpos.syscall.fs.LinuxDirent64
import org.plos_clan.cpos.syscall.fs.LinuxFileStatus
import org.plos_clan.cpos.syscall.fs.LinuxStat
import org.plos_clan.cpos.syscall.fs.LinuxStatFs
import org.plos_clan.cpos.syscall.fs.LinuxStatx
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatStructuresTest {
    private val status = LinuxFileStatus(
        fileSystemDevice = checkNotNull(DeviceNumber.create(0x123u, 0x45678u)),
        inodeId = 0x0102_0304_0506_0708uL,
        type = InodeType.REGULAR,
        metadata = InodeMetadata(
            mode = FileMode(0x1A0u),
            size = 0x1122_3344_5566uL,
            linkCount = 3u,
            deviceNumber = 0x543A_BC21uL,
            uid = 1000u,
            gid = 1001u,
            timestamps = InodeTimestamps(
                accessTime = VfsTimestamp(-2, 111u),
                modificationTime = VfsTimestamp(2, 222u),
                changeTime = VfsTimestamp(3, 333u),
                birthTime = VfsTimestamp(1, 444u),
            ),
        ),
        blocks = 0x1234uL,
        blockSize = 8192uL,
    )

    @Test
    fun serializesLinuxStatLayout() {
        val bytes = LinuxStat(status).toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(FsConstants.STAT_SIZE, bytes.size)
        assertEquals(status.fileSystemDevice.value, input.readU64(0))
        assertEquals(status.inodeId, input.readU64(8))
        assertEquals(3uL, input.readU64(16))
        assertEquals(0x81A0u, input.readU32(24))
        assertEquals(1000u, input.readU32(28))
        assertEquals(1001u, input.readU32(32))
        assertEquals(0u, input.readU32(36))
        assertEquals(0x543A_BC21uL, input.readU64(40))
        assertEquals(0x1122_3344_5566uL, input.readU64(48))
        assertEquals(8192uL, input.readU64(56))
        assertEquals(0x1234uL, input.readU64(64))
        assertEquals(-2L, input.readU64(72).toLong())
        assertEquals(111uL, input.readU64(80))
        assertEquals(2L, input.readU64(88).toLong())
        assertEquals(222uL, input.readU64(96))
        assertEquals(3L, input.readU64(104).toLong())
        assertEquals(333uL, input.readU64(112))
        assertTrue(bytes.copyOfRange(120, bytes.size).all { it == 0.toByte() })
    }

    @Test
    fun serializesLinuxStatxLayout() {
        val bytes = LinuxStatx(status, isMountRoot = false, mountId = 42uL).toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(FsConstants.STATX_SIZE, bytes.size)
        assertEquals(
            FsConstants.STATX_SUPPORTED_FIELDS or FsConstants.STATX_BTIME,
            input.readU32(0),
        )
        assertEquals(
            FsConstants.STATX_MNT_ID,
            input.readU32(0) and FsConstants.STATX_MNT_ID,
        )
        assertEquals(8192u, input.readU32(4))
        assertEquals(0uL, input.readU64(8))
        assertEquals(3u, input.readU32(16))
        assertEquals(1000u, input.readU32(20))
        assertEquals(1001u, input.readU32(24))
        assertEquals(0x81A0u.toUShort(), input.readU16(28))
        assertEquals(status.inodeId, input.readU64(32))
        assertEquals(0x1122_3344_5566uL, input.readU64(40))
        assertEquals(0x1234uL, input.readU64(48))
        assertEquals(FsConstants.STATX_ATTR_MOUNT_ROOT, input.readU64(56))
        assertEquals(-2L, input.readU64(64).toLong())
        assertEquals(111u, input.readU32(72))
        assertEquals(1L, input.readU64(80).toLong())
        assertEquals(444u, input.readU32(88))
        assertEquals(3L, input.readU64(96).toLong())
        assertEquals(333u, input.readU32(104))
        assertEquals(2L, input.readU64(112).toLong())
        assertEquals(222u, input.readU32(120))
        assertEquals(0xABCu, input.readU32(128))
        assertEquals(0x54321u, input.readU32(132))
        assertEquals(0x123u, input.readU32(136))
        assertEquals(0x45678u, input.readU32(140))
        assertEquals(42uL, input.readU64(144))
        assertTrue(bytes.copyOfRange(152, bytes.size).all { it == 0.toByte() })
    }

    @Test
    fun marksLinuxStatxMountRoot() {
        val bytes = LinuxStatx(status, isMountRoot = true, mountId = 42uL).toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(FsConstants.STATX_ATTR_MOUNT_ROOT, input.readU64(8))
        assertEquals(FsConstants.STATX_ATTR_MOUNT_ROOT, input.readU64(56))
    }

    @Test
    fun omitsAbsentBirthTimeFromStatx() {
        val withoutBirthTime = status.copy(
            metadata = status.metadata.copy(
                timestamps = status.metadata.timestamps.copy(birthTime = null),
            ),
        )
        val bytes = LinuxStatx(
            withoutBirthTime,
            isMountRoot = false,
            mountId = 42uL,
        ).toNativeBytes()

        assertEquals(
            FsConstants.STATX_SUPPORTED_FIELDS,
            LittleEndianBuffer(bytes).readU32(0),
        )
        assertTrue(bytes.copyOfRange(80, 96).all { it == 0.toByte() })
    }

    @Test
    fun serializesAlignedDirectoryEntriesAndTypes() {
        val name = assertIs<VfsResult.Ok<VfsName>>(
            VfsName.fromBytes("kernel".encodeToByteArray()),
        ).value
        val entry = DirectoryEntry(name, InodeId(42uL), InodeType.DIRECTORY)
        val record = LinuxDirent64(entry, nextOffset = -1)
        val bytes = record.toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(32, record.recordSize)
        assertEquals(record.recordSize, bytes.size)
        assertEquals(42uL, input.readU64(0))
        assertEquals(-1L, input.readU64(8).toLong())
        assertEquals(32u.toUShort(), input.readU16(16))
        assertEquals(4, bytes[18].toInt())
        assertContentEquals("kernel".encodeToByteArray(), bytes.copyOfRange(19, 25))
        assertTrue(bytes.copyOfRange(25, bytes.size).all { it == 0.toByte() })

        listOf(
            InodeType.PIPE to 1,
            InodeType.CHARACTER_DEVICE to 2,
            InodeType.DIRECTORY to 4,
            InodeType.BLOCK_DEVICE to 6,
            InodeType.REGULAR to 8,
            InodeType.SYMLINK to 10,
            InodeType.SOCKET to 12,
        ).forEach { (type, value) ->
            val encoded = LinuxDirent64(entry.copy(type = type), 0).toNativeBytes()
            assertEquals(value, encoded[18].toInt(), "type=$type")
        }
    }

    @Test
    fun serializesBackendFileSystemStatistics() {
        val bytes = LinuxStatFs(
            fileSystemMagic = 0x1234uL,
            mountFlags = MountFlags.of(MountFlag.READ_ONLY, MountFlag.NO_EXEC),
            statistics = FileSystemStatistics(
                blockSize = 8192uL,
                fragmentSize = 4096uL,
                blocks = 100uL,
                freeBlocks = 40uL,
                availableBlocks = 30uL,
                files = 20uL,
                freeFiles = 10uL,
                maximumNameLength = 127uL,
            ),
        ).toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(0x1234uL, input.readU64(0))
        assertEquals(8192uL, input.readU64(8))
        assertEquals(100uL, input.readU64(16))
        assertEquals(40uL, input.readU64(24))
        assertEquals(30uL, input.readU64(32))
        assertEquals(20uL, input.readU64(40))
        assertEquals(10uL, input.readU64(48))
        assertEquals(127uL, input.readU64(64))
        assertEquals(4096uL, input.readU64(72))
        assertEquals(FsConstants.ST_RDONLY or FsConstants.ST_NOEXEC, input.readU64(80))
    }

    @Test
    fun anonymousDescriptorsHaveNoFilesystemObjectType() {
        val metadata = status.metadata.copy(mode = FileMode(0x1c0u))

        listOf(
            InodeType.EVENTFD,
            InodeType.EPOLL,
            InodeType.INOTIFY,
            InodeType.PIDFD,
        ).forEach { type ->
            assertEquals(0x1c0u, status.copy(type = type, metadata = metadata).mode)
        }
    }
}
