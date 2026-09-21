package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.CreateDisposition
import org.plos_clan.cpos.fs.vfs.FileLock
import org.plos_clan.cpos.fs.vfs.FileLockDomain
import org.plos_clan.cpos.fs.vfs.FileLockRange
import org.plos_clan.cpos.fs.vfs.FileLockMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLocksTest {
    @Test
    fun sharedLocksExcludeWritersAndFailedConversionDropsTheOldLock() {
        val files = Files()
        try {
            files.acquire(files.first, FileLockMode.SHARED)
            files.acquire(files.second, FileLockMode.SHARED)
            val result = files.locks.acquire(files.first, FileLockMode.EXCLUSIVE, true)
            assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), result)
            files.acquire(files.second, FileLockMode.EXCLUSIVE)
            assertEquals(
                VfsResult.Err(VfsError.WOULD_BLOCK),
                files.locks.acquire(files.first, FileLockMode.SHARED, true),
            )
            files.locks.release(files.second)
            files.acquire(files.first, FileLockMode.EXCLUSIVE)
            files.acquire(files.first, FileLockMode.EXCLUSIVE)
        } finally {
            files.close()
        }
    }

    @Test
    fun duplicatedDescriptionsHoldLocksUntilTheLastReferenceCloses() {
        val files = Files()
        try {
            files.acquire(files.first, FileLockMode.EXCLUSIVE)
            assertTrue(files.first.retain())
            files.first.release()
            assertEquals(
                VfsResult.Err(VfsError.WOULD_BLOCK),
                files.locks.acquire(files.second, FileLockMode.EXCLUSIVE, true),
            )
            files.first.release()
            files.acquire(files.second, FileLockMode.EXCLUSIVE)
            files.locks.release(files.second)
            files.locks.release(files.second)
        } finally {
            files.close()
        }
    }

    @Test
    fun ofdRangesSplitMergeAndRemainIndependentOfFlock() {
        val files = Files()
        val domain = FileLockDomain.OFD
        val whole = FileLockRange(0, 99)
        val middle = FileLockRange(20, 79)
        try {
            files.acquire(files.second, FileLockMode.EXCLUSIVE)
            assertIs<VfsResult.Ok<Unit>>(files.locks.acquire(
                files.first, FileLockMode.EXCLUSIVE, true, domain, whole,
            ))
            assertIs<VfsResult.Ok<Unit>>(files.locks.acquire(
                files.first, null, true, domain, middle,
            ))
            val middleQuery = FileLock(FileLockMode.EXCLUSIVE, middle)
            assertNull(files.locks.query(files.second, middleQuery))
            val left = FileLockRange(0, 19)
            val right = FileLockRange(80, 99)
            val leftQuery = FileLock(FileLockMode.EXCLUSIVE, left)
            val rightQuery = FileLock(FileLockMode.EXCLUSIVE, right)
            assertEquals(leftQuery, files.locks.query(files.second, leftQuery))
            assertEquals(rightQuery, files.locks.query(files.second, rightQuery))
            assertIs<VfsResult.Ok<Unit>>(files.locks.acquire(
                files.first, FileLockMode.EXCLUSIVE, true, domain, middle,
            ))
            val merged = FileLock(FileLockMode.EXCLUSIVE, whole)
            assertEquals(merged, files.locks.query(files.second, leftQuery))
            assertNull(files.locks.query(files.first, merged))
        } finally {
            files.close()
        }
    }

    @Test
    fun failedOfdUpgradePreservesTheExistingSharedRange() {
        val files = Files()
        val domain = FileLockDomain.OFD
        val range = FileLockRange(10, Long.MAX_VALUE)
        try {
            assertIs<VfsResult.Ok<Unit>>(files.locks.acquire(
                files.first, FileLockMode.SHARED, true, domain, range,
            ))
            assertIs<VfsResult.Ok<Unit>>(files.locks.acquire(
                files.second, FileLockMode.SHARED, true, domain, range,
            ))
            assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), files.locks.acquire(
                files.first, FileLockMode.EXCLUSIVE, true, domain, range,
            ))
            val shared = FileLock(FileLockMode.SHARED, range)
            val requested = FileLock(FileLockMode.EXCLUSIVE, range)
            assertEquals(shared, files.locks.query(files.second, requested))
        } finally {
            files.close()
        }
    }

    private class Files {
        private val vfs = Vfs()
        private val caller = VfsOperationContext.KERNEL
        private val context: FileSystemContext
        val first: OpenFileDescription
        val second: OpenFileDescription
        val locks get() = first.inode.superBlock.fileLocks

        init {
            assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
            context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
            val pathname = VfsPathname.fromString("/file")
            val options = OpenOptions(create = CreateDisposition.OPEN_OR_CREATE)
            first = assertIs<VfsResult.Ok<OpenFileDescription>>(
                vfs.open(caller, context, pathname, options),
            ).value
            second = assertIs<VfsResult.Ok<OpenFileDescription>>(
                vfs.open(caller, context, pathname),
            ).value
        }

        fun acquire(file: OpenFileDescription, mode: FileLockMode) {
            assertIs<VfsResult.Ok<Unit>>(locks.acquire(file, mode, true))
        }

        fun close() {
            if (first.isOpen) first.release()
            second.release()
            context.release()
        }
    }
}
