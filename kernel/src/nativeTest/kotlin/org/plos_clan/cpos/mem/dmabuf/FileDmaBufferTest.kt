package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.fs.tmpfs.MemFd
import org.plos_clan.cpos.fs.tmpfs.MemFdFlags
import org.plos_clan.cpos.fs.vfs.FileAllocationMode
import org.plos_clan.cpos.fs.vfs.FilePageRange
import org.plos_clan.cpos.fs.vfs.FileSeals
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.PinnableFile
import org.plos_clan.cpos.fs.vfs.PinnedFilePages
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.SealableFile
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FileDmaBufferTest {
    private class Fixture : AutoCloseable {
        val caller = VfsOperationContext.KERNEL
        val file: OpenFileDescription
        val provider: PinnableFile
        val seals: SealableFile

        init {
            val flags = assertIs<VfsResult.Ok<MemFdFlags>>(MemFdFlags.from(MemFdFlags.ALLOW_SEALING)).value
            val opened = MemFd.create(caller, "pin-test".encodeToByteArray(), flags)
            file = assertIs<VfsResult.Ok<OpenFileDescription>>(opened).value
            val backend = assertIs<RegularFileBackend>(file.inode.backend)
            assertIs<VfsResult.Ok<Unit>>(backend.resize(caller, file.inode, PAGE_SIZE_BYTES * 3uL))
            provider = assertIs<PinnableFile>(backend)
            seals = assertIs<SealableFile>(backend)
        }

        fun seal(bits: Int): VfsResult<Unit> = seals.addSeals(file.inode, bits)

        fun tryPin(offset: ULong, size: ULong): VfsResult<PinnedFilePages> {
            val range = when (val result = FilePageRange.create(offset, size)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            return provider.pin(file, range)
        }

        fun pin(offset: ULong = 0uL, size: ULong = PAGE_SIZE_BYTES): PinnedFilePages {
            val result = tryPin(offset, size)
            return assertIs<VfsResult.Ok<PinnedFilePages>>(result).value
        }

        override fun close() = file.release()
    }

    @Test
    fun pinningRequiresStableSizeAndWritableSeals() {
        Fixture().use { fixture ->
            val missingSeal = fixture.tryPin(0uL, PAGE_SIZE_BYTES)
            assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), missingSeal)
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.SHRINK))
            val pin = fixture.pin()
            assertEquals(VfsResult.Err(VfsError.BUSY), fixture.seal(FileSeals.WRITE))
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.FUTURE_WRITE))
            pin.pages.single().fill(0, 8, 42)
            val sealed = fixture.tryPin(0uL, PAGE_SIZE_BYTES)
            assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), sealed)
            pin.close()
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.WRITE))
        }
    }

    @Test
    fun failedRangesDoNotBlockWriteSealing() {
        Fixture().use { fixture ->
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.SHRINK))
            val ranges = listOf(0uL to 0uL, 1uL to PAGE_SIZE_BYTES, 0uL to 1uL,
                PAGE_SIZE_BYTES * 3uL to PAGE_SIZE_BYTES, ULong.MAX_VALUE to PAGE_SIZE_BYTES)
            for ((offset, size) in ranges) {
                val result = fixture.tryPin(offset, size)
                assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), result)
            }
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.WRITE))
        }
    }

    @Test
    fun pinnedPagesSurviveHolePunchAndRetainTheSourceFile() {
        val fixture = Fixture()
        assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.SHRINK))
        val pin = fixture.pin(PAGE_SIZE_BYTES)
        pin.pages.single().fill(0, 8, 42)
        val pins = listOf(pin)
        val buffer = FileDmaBuffer(pins)
        val punched = fixture.file.allocate(
            fixture.caller, PAGE_SIZE_BYTES, PAGE_SIZE_BYTES, FileAllocationMode.PUNCH_HOLE,
        )
        assertIs<VfsResult.Ok<Unit>>(punched)
        val replacement = fixture.pin(PAGE_SIZE_BYTES)
        val bytes = ByteArray(8)
        replacement.pages.single().copyTo(0, bytes, 0, bytes.size)
        assertEquals(List(8) { 0.toByte() }, bytes.toList())
        replacement.close()
        fixture.close()
        assertEquals(8, buffer.read(0uL, bytes))
        assertEquals(List(8) { 42.toByte() }, bytes.toList())
        buffer.release()
        assertFalse(fixture.file.retain())
    }

    @Test
    fun duplicateAndReorderedRangesShareTheSamePages() {
        Fixture().use { fixture ->
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.SHRINK))
            val first = fixture.pin(PAGE_SIZE_BYTES)
            val second = fixture.pin(0uL)
            val third = fixture.pin(PAGE_SIZE_BYTES)
            first.pages.single().fill(0, 8, 17)
            second.pages.single().fill(0, 8, 23)
            val pins = listOf(first, second, third)
            val buffer = FileDmaBuffer(pins)
            val bytes = ByteArray(8)
            for ((index, expected) in listOf(17, 23, 17).withIndex()) {
                assertEquals(8, buffer.read(index.toULong() * PAGE_SIZE_BYTES, bytes))
                assertEquals(List(8) { expected.toByte() }, bytes.toList())
            }
            buffer.release()
            assertIs<VfsResult.Ok<Unit>>(fixture.seal(FileSeals.WRITE))
        }
    }
}
