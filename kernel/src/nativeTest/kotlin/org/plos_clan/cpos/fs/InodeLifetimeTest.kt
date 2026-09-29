@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeAttributeSnapshot
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Scheduler
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.runtime.GC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InodeLifetimeTest {
    @Test
    fun resourcesAreReleasedAfterTheLastInodeOwnerDisappears() {
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
        val released = AtomicInt(0)
        try {
            val owner = createOwner(context, released)
            GC.collect()
            assertEquals(0, released.load())
            owner[0] = null
            GC.collect()
            val deadline = TscClock.nanoTime() + 5_000_000_000uL
            while (true) {
                val completed = released.load() != 0
                if (completed) break
                assertTrue(TscClock.nanoTime() < deadline, "Inode resource was not released")
                Scheduler.yieldCurrent()
            }
            assertEquals(1, released.load())
        } finally {
            context.release()
        }
    }

    private fun createOwner(context: FileSystemContext, released: AtomicInt): Array<Inode?> {
        val root = checkNotNull(context.root.inode)
        val attributes = InodeAttributes(root.metadata())
        val snapshot = InodeAttributeSnapshot(attributes, CacheValidity.Persistent)
        val resource = AutoCloseable { released.fetchAndAdd(1) }
        val inode = Inode(root.id, root.superBlock, root.backend, snapshot, resource = resource)
        return arrayOf(inode)
    }
}
