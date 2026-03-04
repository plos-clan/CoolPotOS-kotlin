package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.PageDirectory

data class Process(
    val id: Int,
    val name: String,
    var directory: PageDirectory
) {
    var threads: List<Thread> = ArrayList()
}

data class Thread(
    val id: Int,
    val name: String,
)

object ProcessManager {
    private var nextProcessId = 0
    private val processes = mutableListOf<Process>()

    fun initialize() {
        if (processes.isNotEmpty()) {
            return
        }
        val systemProcess = createProcess("System", false)
        processes += systemProcess
        println("ProcessManager initialized PID=${systemProcess.id} Name=${systemProcess.name}")
    }

    private fun createProcess(name: String, clone: Boolean): Process =
        Process(
            id = nextProcessId++,
            name = name,
            directory = KernelPageDirectory.getDirectory()
        )
}
