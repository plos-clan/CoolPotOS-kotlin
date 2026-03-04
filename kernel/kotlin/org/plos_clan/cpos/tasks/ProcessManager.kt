@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.utils.PtraceRegisters

data class Process(
    val id: Int,
    val name: String,
    var directory: PageDirectory
) {
    var threads: List<Thread> = ArrayList()

    fun addThread(thread: Thread) {
        threads += thread
    }
}

data class Thread(
    val id: Int,
    val name: String,
){
    var registers: PtraceRegisters? = null
}

object ProcessManager {
    private var nextProcessId = 0
    private val processes = mutableListOf<Process>()

    fun initialize() {
        if (processes.isNotEmpty()) {
            return
        }
        val systemProcess = createKernelProcess("System")
        processes += systemProcess
        println("ProcessManager initialized PID=${systemProcess.id} Name=${systemProcess.name}")
    }

    fun createProcess(name: String, clone : Boolean): Process = Process(
        id = nextProcessId++,
        name = name,
        directory = KernelPageDirectory.getDirectory()
    )

    private fun createKernelProcess(name: String): Process =
        Process(
            id = nextProcessId++,
            name = name,
            directory = KernelPageDirectory.getDirectory()
        )
}
