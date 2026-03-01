package org.plos_clan.cpos.tasks

object ProcessManager {
    private var nextProcessId = 0
    private val processes = mutableListOf<Process>()

    fun initialize() {
        if (processes.isNotEmpty()) {
            return
        }
        val systemProcess = createProcess("System")
        processes += systemProcess
        println("ProcessManager initialized PID=${systemProcess.id}")
    }

    private fun createProcess(name: String): Process =
        Process(
            id = nextProcessId++,
            name = name,
        )
}
