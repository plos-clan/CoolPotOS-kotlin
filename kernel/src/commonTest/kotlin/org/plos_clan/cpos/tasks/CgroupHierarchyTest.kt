package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.cgroup.CgroupHierarchy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CgroupHierarchyTest {
    private val events = mutableListOf<Pair<CgroupHierarchy.Group, CgroupHierarchy.Event>>()
    private val hierarchy = CgroupHierarchy { group, event -> events += group to event }
    private val root = hierarchy.root

    private fun group(name: String, parent: CgroupHierarchy.Group = root) =
        hierarchy.create(parent, value(VfsName.fromBytes(name.encodeToByteArray()))).let(::value)

    private fun task(id: Int, pid: Int = id, parent: CgroupHierarchy.Task? = null) =
        hierarchy.fork(id, pid, parent).let(::value)

    private fun <T> value(result: VfsResult<T>): T = (result as VfsResult.Ok).value

    @Test
    fun inheritsMembershipAndAccountsEveryThreadUntilExit() {
        val service = group("service")
        val leader = task(1)
        value(hierarchy.move(service, listOf(leader), thread = false))
        val sibling = task(2, 1, leader)
        val child = task(3, parent = sibling)

        assertSame(service, child.group)
        assertEquals(3, service.taskCount)
        assertEquals(3, root.taskCount)
        hierarchy.exit(leader)
        assertEquals(setOf(sibling), hierarchy.process(1))
        assertEquals(VfsResult.Err(VfsError.BUSY), hierarchy.remove(service))
        hierarchy.exit(sibling)
        hierarchy.exit(child)
        hierarchy.exit(child)
        assertEquals(0, root.taskCount)
        value(hierarchy.remove(service))
        assertFalse(service.live)
    }

    @Test
    fun enforcesAncestorPidsLimitsAndRollsBackRejectedFork() {
        value(hierarchy.subtreeControl(root, "+pids"))
        val slice = group("slice")
        value(hierarchy.subtreeControl(slice, "+pids"))
        val service = group("service", slice)
        slice.pidsMax = 2
        val leader = task(1)
        value(hierarchy.move(service, listOf(leader), false))
        val sibling = task(2, 1, leader)

        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.fork(3, 3, sibling))
        assertEquals(2, root.taskCount)
        assertEquals(2, slice.taskCount)
        assertEquals(2, service.taskCount)
        assertEquals(1, slice.pidsEvents)
        assertEquals(1, slice.pidsEventsLocal)
        assertEquals(0, service.pidsEvents)
        assertEquals(null, hierarchy.task(3))
        hierarchy.exit(sibling)
        task(3, parent = leader)
        assertEquals(2, slice.pidsPeak)
    }

    @Test
    fun migrationCanExceedLimitButFurtherForkCannot() {
        value(hierarchy.subtreeControl(root, "+pids"))
        val service = group("service")
        service.pidsMax = 0
        val leader = task(1)
        task(2, 1, leader)
        value(hierarchy.move(service, hierarchy.process(1).toList(), false))

        assertEquals(2, service.taskCount)
        assertTrue(hierarchy.process(1).all { it.group === service })
        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.fork(3, 3, leader))
        assertEquals(0, service.pidsMax)
    }

    @Test
    fun siblingMigrationDoesNotReportCommonAncestorAsEmpty() {
        val slice = group("slice")
        val first = group("first", slice)
        val second = group("second", slice)
        val leader = task(1)
        value(hierarchy.move(first, listOf(leader), false))
        events.clear()
        value(hierarchy.move(second, listOf(leader), false))

        assertEquals(listOf(first, second), events.map { it.first })
        assertEquals(1, slice.taskCount)
        assertEquals(1, slice.pidsPeak)
    }

    @Test
    fun subtreeControlIsAtomicAndUsesLastOperation() {
        value(hierarchy.subtreeControl(root, "+pids -pids +pids"))
        assertTrue(CgroupHierarchy.Controller.PIDS in root.subtreeControl)
        assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), hierarchy.subtreeControl(root, "-pids +memory"))
        assertTrue(CgroupHierarchy.Controller.PIDS in root.subtreeControl)
        val slice = group("slice")
        value(hierarchy.subtreeControl(slice, "+pids"))
        assertEquals(VfsResult.Err(VfsError.BUSY), hierarchy.subtreeControl(root, "-pids"))
        value(hierarchy.subtreeControl(slice, "-pids"))
        slice.pidsMax = 4
        value(hierarchy.subtreeControl(root, "-pids"))
        assertFalse(slice.hasPids)
        assertEquals(Long.MAX_VALUE, slice.pidsMax)
        assertEquals(VfsResult.Err(VfsError.NOT_FOUND), hierarchy.subtreeControl(slice, "+pids"))
    }

    @Test
    fun limitsDepthAndDescendantsRelativeToEachAncestor() {
        root.maxDepth = 2
        root.maxDescendants = 2
        val parent = group("parent")
        val child = group("child", parent)
        val name = value(VfsName.fromBytes("next".encodeToByteArray()))
        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.create(child, name))
        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.create(root, name))
        assertEquals(VfsResult.Err(VfsError.NOT_EMPTY), hierarchy.remove(parent))
        value(hierarchy.remove(child))
        value(hierarchy.create(parent, name))
        assertEquals(2, root.descendants)
    }

    @Test
    fun threadedDomainsConstrainIndividualThreadMigration() {
        val domain = group("domain")
        val first = group("first", domain)
        val second = group("second", domain)
        value(hierarchy.enableThreaded(first))
        assertEquals(CgroupHierarchy.Type.THREADED_DOMAIN, domain.type)
        assertEquals(CgroupHierarchy.Type.INVALID, second.type)
        value(hierarchy.enableThreaded(second))
        val leader = task(1)
        val sibling = task(2, 1, leader)
        value(hierarchy.move(first, hierarchy.process(1).toList(), false))
        value(hierarchy.move(second, listOf(sibling), true))

        assertSame(domain, sibling.group.domain)
        assertSame(first, leader.group)
        assertSame(second, sibling.group)
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.move(root, listOf(sibling), true))
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.enableThreaded(domain))
        value(hierarchy.move(root, hierarchy.process(1).toList(), false))
        value(hierarchy.remove(first))
        value(hierarchy.remove(second))
        assertEquals(CgroupHierarchy.Type.DOMAIN, domain.type)
    }

    @Test
    fun populatedDomainsCannotBecomeThreadedOrEnableInternalCompetition() {
        val domain = group("domain")
        val child = group("child", domain)
        val leader = task(1)
        value(hierarchy.move(child, listOf(leader), false))
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.enableThreaded(child))
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.enableThreaded(group("sibling", domain)))
        value(hierarchy.subtreeControl(root, "+pids"))
        val other = task(2)
        value(hierarchy.move(domain, listOf(other), false))
        assertEquals(VfsResult.Err(VfsError.BUSY), hierarchy.subtreeControl(domain, "+pids"))
        assertTrue(domain.subtreeControl.isEmpty())
    }

    @Test
    fun internalThreadedControllerInvalidatesNewDomainChildren() {
        value(hierarchy.subtreeControl(root, "+pids"))
        val parent = group("parent")
        val leader = task(1)
        value(hierarchy.move(parent, listOf(leader), false))
        value(hierarchy.subtreeControl(parent, "+pids"))
        val child = group("child", parent)
        assertEquals(CgroupHierarchy.Type.THREADED_DOMAIN, parent.type)
        assertEquals(CgroupHierarchy.Type.INVALID, child.type)
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.move(child, listOf(leader), false))
        value(hierarchy.enableThreaded(child))
        value(hierarchy.move(child, listOf(leader), false))
    }

    @Test
    fun freezesOnlyAfterAllTasksAcknowledgeAndPreservesNestedRequests() {
        val parent = group("parent")
        val child = group("child", parent)
        val leader = task(1)
        value(hierarchy.move(child, listOf(leader), false))
        val sibling = task(2, 1, leader)
        hierarchy.freeze(parent, true)
        assertTrue(leader.freezing)
        assertFalse(parent.frozen)
        hierarchy.acknowledgeFreeze(leader, true)
        assertFalse(child.frozen)
        hierarchy.acknowledgeFreeze(sibling, true)
        assertTrue(parent.frozen)
        assertTrue(child.frozen)
        hierarchy.freeze(child, true)
        hierarchy.freeze(parent, false)
        assertFalse(parent.frozen)
        assertTrue(child.frozen)
        assertTrue(leader.freezing)
        hierarchy.freeze(child, false)
        assertFalse(child.frozen)
        assertFalse(leader.freezing)
        hierarchy.acknowledgeFreeze(leader, false)
        hierarchy.acknowledgeFreeze(sibling, false)
        assertEquals(0, root.frozenCount)
    }

    @Test
    fun forkAndMigrationInvalidateFrozenCompletionUntilAcknowledged() {
        val frozen = group("frozen")
        hierarchy.freeze(frozen, true)
        assertTrue(frozen.frozen)
        val leader = task(1)
        value(hierarchy.move(frozen, listOf(leader), false))
        assertTrue(leader.freezing)
        assertFalse(frozen.frozen)
        hierarchy.acknowledgeFreeze(leader, true)
        val child = task(2, parent = leader)
        assertTrue(child.freezing)
        assertFalse(frozen.frozen)
        hierarchy.exit(child)
        assertTrue(frozen.frozen)
        value(hierarchy.move(root, listOf(leader), false))
        assertFalse(leader.freezing)
        hierarchy.acknowledgeFreeze(leader, false)
        assertTrue(frozen.frozen)
        assertEquals(0, root.frozenCount)
    }

    @Test
    fun killedReservationsCannotForkAndDeletedGroupsCannotBeReused() {
        val service = group("service")
        val leader = task(1)
        value(hierarchy.move(service, listOf(leader), false))
        leader.killed = true
        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.fork(2, 2, leader))
        hierarchy.exit(leader)
        value(hierarchy.remove(service))
        val replacement = group("service")
        assertFalse(service === replacement)
        assertEquals(VfsResult.Err(VfsError.NO_DEVICE), hierarchy.move(service, listOf(task(2)), false))
        assertEquals("/service", service.path().decodeToString())
    }

    @Test
    fun cloneIntoCgroupChargesDestinationAndInheritsItsFreezer() {
        value(hierarchy.subtreeControl(root, "+pids"))
        val source = group("source")
        val destination = group("destination")
        val leader = task(1)
        value(hierarchy.move(source, listOf(leader), false))
        source.pidsMax = 0
        destination.pidsMax = 1
        hierarchy.freeze(destination, true)
        val child = value(hierarchy.fork(2, 2, leader, destination))
        assertSame(destination, child.group)
        assertTrue(child.freezing)
        assertEquals(1, destination.taskCount)
        assertEquals(1, source.taskCount)
        assertEquals(VfsResult.Err(VfsError.WOULD_BLOCK), hierarchy.fork(3, 3, leader, destination))
        hierarchy.exit(child)
        assertTrue(destination.frozen)
        assertEquals(VfsResult.Err(VfsError.NOT_SUPPORTED), hierarchy.fork(3, 1, leader, destination, thread = true))
    }

    @Test
    fun rootThreadedDomainDoesNotIncludeChildDomains() {
        value(hierarchy.subtreeControl(root, "+pids"))
        val leader = task(1)
        val domain = group("domain")
        val threaded = group("threaded")
        value(hierarchy.enableThreaded(threaded))
        value(hierarchy.move(domain, listOf(leader), false))
        assertEquals(setOf(root, threaded), root.subtree(threadedOnly = true).toSet())
        assertEquals(setOf(root, domain, threaded), root.subtree().toSet())
    }

    @Test
    fun staleExitCannotRemoveAReplacementTask() {
        val old = task(1)
        hierarchy.exit(old)
        val replacement = task(1)
        hierarchy.exit(old)
        assertSame(replacement, hierarchy.task(1))
        assertEquals(1, root.taskCount)
    }
}
