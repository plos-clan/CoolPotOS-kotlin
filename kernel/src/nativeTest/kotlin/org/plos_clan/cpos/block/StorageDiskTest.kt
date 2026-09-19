@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.block

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.syscall.fs.lseek
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.PtraceRegisters

class StorageDiskTest {
    @Test
    fun partitionReadWriteFlushAndConcurrentCommands() {
        val selector = assertNotNull(Cmdline["verify.storage"])
        val device = assertNotNull(KernelCoroutines.await { BlockDevices.await(selector) })
        val volume = (device.backend as BlockDeviceBackend).volume
        val original = ByteArray(131072)
        assertTrue(volume.read(0uL, original))
        val data = ByteArray(original.size) { (it * 37 + 11).toByte() }
        val input = ByteArrayBuffer(data).prepareRead(0, data.size)!!
        try {
            assertEquals(
                BlockResult(BlockStatus.SUCCESS, data.size - 14),
                volume.write(7uL, input, 7, data.size - 14),
            )
            assertEquals(BlockStatus.SUCCESS, volume.cache.flush())
            val actual = ByteArray(data.size)
            assertTrue(volume.read(0uL, actual))
            val expected = original.copyOf()
            data.copyInto(expected, 7, 7, data.size - 7)
            assertContentEquals(expected, actual)
            val raw = BlockBytes(volume.cache.device)
            KernelCoroutines.await {
                coroutineScope {
                    (0 until 8)
                        .map { index ->
                            async {
                                val bytes = ByteArray(4096)
                                val position = index * 4096
                                val result =
                                    raw.transfer(
                                        BlockOperation.READ,
                                        volume.offset + position.toUInt(),
                                        ByteArrayBuffer(bytes),
                                        0,
                                        bytes.size,
                                    )
                                assertEquals(BlockResult(BlockStatus.SUCCESS, bytes.size), result)
                                assertContentEquals(
                                    expected.copyOfRange(position, position + bytes.size),
                                    bytes,
                                )
                            }
                        }
                        .awaitAll()
                }
            }
            val boundary = ByteArrayBuffer(ByteArray(16)).prepareWrite(0, 16)!!
            assertEquals(3, volume.read(volume.size - 3uL, boundary, 0, 16).bytes)
            assertEquals(0, volume.read(volume.size, boundary, 0, 16).bytes)
        } finally {
            val restore = ByteArrayBuffer(original).prepareRead(0, original.size)!!
            assertEquals(original.size, volume.write(0uL, restore, 0, original.size).bytes)
            assertEquals(BlockStatus.SUCCESS, volume.cache.flush())
        }
    }

    @Test
    fun blockDeviceSelectors() {
        val source = assertNotNull(Cmdline["verify.storage"])
        val device = assertNotNull(KernelCoroutines.await { BlockDevices.await(source) })
        assertSame(device, BlockDevices.find(source.uppercase()))
        assertSame(device, BlockDevices.find(device.name))
        assertSame(device, BlockDevices.find("/dev/${device.name}"))
        for (invalid in listOf("PARTUUID=", "PARTUUID=null", "PARTUUID=invalid", "/dev/missing")) {
            assertNull(BlockDevices.find(invalid))
        }
    }

    @Test
    fun erofsRootComesFromDisk() {
        assertTrue(FileSystemManager.mountRootfs())
        val source = assertNotNull(Cmdline["root"])
        val device = assertNotNull(BlockDevices.find(source))
        val volume = (device.backend as BlockDeviceBackend).volume
        val magic = ByteArray(4)
        assertTrue(volume.read(1024uL, magic))
        assertContentEquals(
            byteArrayOf(0xe2.toByte(), 0xe1.toByte(), 0xf5.toByte(), 0xe0.toByte()),
            magic,
        )
    }

    @Test
    fun blockDeviceSeekAndMountedRootProtection() = memScoped {
        assertTrue(FileSystemManager.mountRootfs())
        val process = assertNotNull(ProcessManager.getKernelProcess())
        val device = assertNotNull(BlockDevices.find(assertNotNull(Cmdline["root"])))
        val volume = (device.backend as BlockDeviceBackend).volume
        val source = ByteArrayBuffer(ByteArray(512)).prepareRead(0, 512)!!
        assertEquals(
            BlockStatus.READ_ONLY,
            volume.cache.write(volume.offset, source, 0, 512).status,
        )
        val opened =
            FileSystemManager.vfs.open(
                process.vfsOperationContext,
                assertNotNull(process.context),
                VfsPathname.fromString("/dev/${device.name}"),
                OpenOptions(),
            )
        val file = (opened as VfsResult.Ok).value
        val descriptor = assertNotNull(process.fdTable.install(file, 0uL))
        try {
            val registers = PtraceRegisters(allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT))
            registers[PtraceRegisters.IDX_RDI] = descriptor.toULong()
            registers[PtraceRegisters.IDX_RSI] = 0uL
            registers[PtraceRegisters.IDX_RDX] = 2uL
            assertEquals(volume.size.toLong(), lseek(registers, process))
            registers[PtraceRegisters.IDX_RSI] = 17uL
            registers[PtraceRegisters.IDX_RDX] = 0uL
            assertEquals(17L, lseek(registers, process))
        } finally {
            process.fdTable.close(process.vfsOperationContext, descriptor)
        }
        Unit
    }
}
