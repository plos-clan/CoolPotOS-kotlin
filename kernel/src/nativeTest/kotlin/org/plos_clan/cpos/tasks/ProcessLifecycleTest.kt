package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.cgroup.Cgroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessLifecycleTest {
    @Test
    fun exitDuringClonePreparationRejectsPublicationAndReleasesMembership() {
        val process = ProcessManager.createUserProcess("clone-exit", terminationSignal = null)
        var threadId = 0
        try {
            val result = ProcessManager.createUserThread(
                process, 0x10000uL, 0x20000uL,
                prepare = { id ->
                    threadId = id
                    assertEquals(emptyList(), process.beginExit(0))
                    VfsResult.Ok(Unit)
                },
            )
            assertEquals(VfsError.INTERRUPTED, assertIs<VfsResult.Err>(result).error)
            assertTrue(process.threads.isEmpty())
            assertNull(ProcessManager.findThread(threadId))
            val members = Cgroups.lock.withLock { Cgroups.hierarchy.process(process.id) }
            assertTrue(members.isEmpty())
        } finally {
            assertTrue(ProcessManager.discardUserProcess(process))
        }
    }

    @Test
    fun parentExitDeliversTheLastRegisteredSignal() {
        val process = ProcessManager.createUserProcess("parent-death", terminationSignal = null)
        val leader = ProcessManager.createUserThread(process, 0x10000uL, 0x20000uL)
        val parent = assertIs<VfsResult.Ok<Thread>>(leader).value
        val created = ProcessManager.createUserThread(process, 0x10000uL, 0x30000uL, parent)
        val child = assertIs<VfsResult.Ok<Thread>>(created).value
        child.signals.mask = Signal.HANGUP.bit
        child.parentDeathSignal = Signal.CHILD
        child.parentDeathSignal = null
        child.parentDeathSignal = Signal.HANGUP
        ProcessManager.finishThreadExit(parent, 0)
        try {
            assertEquals(Signal.HANGUP.bit, child.pendingSignalMask)
        } finally {
            ProcessManager.finishThreadExit(child, 0)
            assertTrue(ProcessManager.reapChild(0, process))
        }
    }

    @Test
    fun publishedThreadParticipatesInExitWhileLaterCloneIsRejected() {
        val process = ProcessManager.createUserProcess("published-exit", terminationSignal = null)
        val result = ProcessManager.createUserThread(process, 0x10000uL, 0x20000uL)
        val thread = assertIs<VfsResult.Ok<Thread>>(result).value
        try {
            assertSame(process, ProcessManager.findProcess(process.id))
            assertSame(thread, ProcessManager.findThread(thread.id))
            assertEquals(listOf(thread), process.beginExit(0))
            val rejected = ProcessManager.createUserThread(process, 0x10000uL, 0x20000uL)
            assertEquals(VfsError.INTERRUPTED, assertIs<VfsResult.Err>(rejected).error)
            assertEquals(listOf(thread), process.threads)
        } finally {
            ProcessManager.finishThreadExit(thread, 0)
            assertSame(thread, ProcessManager.findThread(thread.id))
            assertTrue(ProcessManager.reapChild(0, process))
            assertNull(ProcessManager.findThread(thread.id))
            assertNull(ProcessManager.findProcess(process.id))
        }
    }
}
