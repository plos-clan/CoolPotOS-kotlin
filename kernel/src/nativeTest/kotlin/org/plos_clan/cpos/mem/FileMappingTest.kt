package org.plos_clan.cpos.mem

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.AnonymousFileFactory
import org.plos_clan.cpos.fs.vfs.FilePosition
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileBackend
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.RegularFileBackend
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.addressspace.FileRegionBacking
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.module.elf.ElfBacking
import org.plos_clan.cpos.module.elf.LoadSegment
import org.plos_clan.cpos.module.elf.ProgramHeader
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileMappingTest {
    private class Source : RegularFileBackend(), OpenFileBackend {
        var failure: VfsError? = VfsError.INTERRUPTED

        override fun open(
            caller: VfsOperationContext,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileBackend> = VfsResult.Ok(this)

        override fun read(
            caller: VfsOperationContext,
            inode: Inode,
            destination: PreparedBufferDestination,
            destinationOffset: Int,
            count: Int,
            position: FilePosition,
        ): IoResult {
            val error = failure
            if (error != null) return IoResult.failure(error)
            val copied = destination.fill(destinationOffset, count, 42)
            position.value += copied
            return IoResult.success(copied)
        }
    }

    private class Fixture : AutoCloseable {
        val source = Source()
        private val context: FileSystemContext
        private val file: OpenFileDescription
        val mappings: List<FileRegionBacking>

        init {
            val caller = VfsOperationContext.KERNEL
            val vfs = Vfs()
            assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
            val created = vfs.createContext(Tmpfs.name)
            context = assertIs<VfsResult.Ok<FileSystemContext>>(created).value
            val metadata = checkNotNull(context.root.inode).metadata()
            val factory = AnonymousFileFactory()
            val inode = factory.createInode(context, source, metadata)
            val options = OpenOptions(access = AccessMode.READ)
            val opened = OpenFileDescription.open(
                caller, context.root, inode, options, openedBackend = source,
            )
            file = assertIs<VfsResult.Ok<OpenFileDescription>>(opened).value
            val header = ProgramHeader(
                type = 1u,
                flags = 4u,
                fileOffset = 0uL,
                virtualAddress = 0uL,
                fileSize = PAGE_SIZE_BYTES,
                memorySize = PAGE_SIZE_BYTES,
                alignment = PAGE_SIZE_BYTES,
            )
            val segment = LoadSegment(header, 0uL, PAGE_SIZE_BYTES, executable = false)
            val segments = listOf(segment)
            val elf = ElfBacking(file, segments)
            val mapped = MappedFile(file, MEMORY_REGION_READABLE)
            mappings = listOf(elf, mapped)
        }

        override fun close() {
            for (mapping in mappings) {
                PageCache.invalidate(mapping.identity)
                mapping.release()
            }
            file.release()
            context.release()
        }
    }

    @Test
    fun interruptedFileAndElfReadsCanBeRetriedWithoutCachingAnIoFailure() {
        val fixture = Fixture()
        fixture.use {
            val scratch = ByteArray(PAGE_SIZE_BYTES.toInt())
            for (mapping in fixture.mappings) {
                fixture.source.failure = VfsError.INTERRUPTED
                val interrupted = PageCache.acquire(mapping, 0uL, scratch)
                assertFalse(interrupted.isSuccess)
                assertEquals(PageCacheFailure.INTERRUPTED, interrupted.failure)
                fixture.source.failure = VfsError.IO
                val failed = PageCache.acquire(mapping, 0uL, scratch)
                assertFalse(failed.isSuccess)
                assertEquals(PageCacheFailure.IO_ERROR, failed.failure)
                fixture.source.failure = null
                val retried = PageCache.acquire(mapping, 0uL, scratch)
                assertTrue(retried.isSuccess)
                PageCache.release(retried.frame)
                assertTrue(scratch.all { it == 42.toByte() })
            }
        }
    }
}
