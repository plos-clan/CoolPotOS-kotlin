package org.plos_clan.cpos.tasks.cgroup

internal data class CpuTime(val user: ULong, val system: ULong) {
    val total get() = user + system

    operator fun plus(other: CpuTime) = CpuTime(user + other.user, system + other.system)
    operator fun minus(other: CpuTime) = CpuTime(user - other.user, system - other.system)

    companion object {
        val ZERO = CpuTime(0uL, 0uL)
    }
}

internal class CpuAccounting<Group : Any, Task : Any>(private val clock: (Task) -> CpuTime) {
    private class Account<Task> {
        val active = mutableMapOf<Task, CpuTime>()
        var settled = CpuTime.ZERO
    }

    private val owners = mutableMapOf<Task, Group>()
    private val groups = mutableMapOf<Group, Account<Task>>()

    fun move(task: Task, destination: Group?) {
        val previous = owners[task]
        if (previous == destination) return
        val now = clock(task)
        if (previous != null) {
            val account = groups.getValue(previous)
            val started = checkNotNull(account.active.remove(task))
            account.settled += now - started
        }
        if (destination == null) {
            owners.remove(task)
            return
        }
        owners[task] = destination
        val account = groups.getOrPut(destination, ::Account)
        account.active[task] = now
    }

    fun remove(group: Group, parent: Group) {
        val removed = groups.remove(group) ?: return
        check(removed.active.isEmpty())
        val destination = groups.getOrPut(parent, ::Account)
        destination.settled += removed.settled
    }

    fun read(subtree: Sequence<Group>): CpuTime {
        var total = CpuTime.ZERO
        for (group in subtree) {
            val account = groups[group] ?: continue
            total += account.settled
            for ((task, started) in account.active) total += clock(task) - started
        }
        return total
    }
}
