package org.plos_clan.cpos.managers

import org.plos_clan.cpos.task.Process

object ProcessManager {
    private var processId = 0
    private val nextProcessId get() = processId++
    private val processes by lazy {// Referring to the ProcessManager object can make this list initialize.
        mutableListOf(
            Process(
                id = nextProcessId,
                name = "System",
            ).also { println("Multi-task initialized ${it.name} PID=${it.id}") }
            // Alternative: Use this if test message printing is not required
            // Process(nextProcessId, "System")
        ).apply {
            sortBy { it.id }
        }
    }

    fun initialize() {
        processes
        println("ProcessManager initialized")
    }
}