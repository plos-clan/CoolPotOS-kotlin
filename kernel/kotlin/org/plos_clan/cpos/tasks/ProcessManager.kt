@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks

import bridge.get_kernel_idle_entry_address
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.alignDown

private const val DEFAULT_THREAD_STACK_PAGES = 8uL
private const val KERNEL_CODE_SELECTOR = 0x08uL
private const val KERNEL_DATA_SELECTOR = 0x10uL
private const val DEFAULT_THREAD_RFLAGS = 0x202uL

private val idleThreadEntryPoint: ULong by lazy {
    get_kernel_idle_entry_address()
}

enum class ThreadState {
    READY,
    RUNNING,
    BLOCKED,
    TERMINATED,
}

data class Process(
    val id: Int,
    val name: String,
    var directory: PageDirectory,
) {
    private val threadList = mutableListOf<Thread>()

    val threads: List<Thread>
        get() = threadList

    fun addThread(thread: Thread) {
        threadList += thread
    }
}

class Thread(
    val id: Int,
    val processId: Int,
    val name: String,
    val stackBasePhysical: ULong,
    val stackSizeBytes: ULong,
    private val context: ULongArray = ULongArray(PtraceRegisters.REGISTER_COUNT),
) {
    var state: ThreadState = ThreadState.READY
    var isQueued: Boolean = false
    var hasSavedContext: Boolean = false
        private set

    fun initializeContext(entryPoint: ULong, stackTop: ULong, argument: ULong = 0uL) {
        context.fill(0uL)
        context[PtraceRegisters.IDX_RIP] = entryPoint
        context[PtraceRegisters.IDX_RSP] = stackTop
        context[PtraceRegisters.IDX_RBP] = stackTop
        context[PtraceRegisters.IDX_RFLAGS] = DEFAULT_THREAD_RFLAGS
        context[PtraceRegisters.IDX_CS] = KERNEL_CODE_SELECTOR
        context[PtraceRegisters.IDX_SS] = KERNEL_DATA_SELECTOR
        context[PtraceRegisters.IDX_DS] = KERNEL_DATA_SELECTOR
        context[PtraceRegisters.IDX_ES] = KERNEL_DATA_SELECTOR
        context[PtraceRegisters.IDX_RDI] = argument
        hasSavedContext = true
        state = ThreadState.READY
    }

    fun saveFrom(registers: PtraceRegisters) {
        registers.copyInto(context)
        hasSavedContext = true
    }

    fun restoreTo(registers: PtraceRegisters): Boolean {
        if (!hasSavedContext) {
            return false
        }
        registers.restoreFrom(context)
        return true
    }
}

object ProcessManager {
    private var nextProcessId = 0
    private var nextThreadId = 0
    private val processes = mutableListOf<Process>()
    private var systemProcess: Process? = null
    private var bootstrapThread: Thread? = null
    private var idleThread: Thread? = null

    fun initialize() {
        if (processes.isNotEmpty()) {
            return
        }

        val kernelProcess = createKernelProcess("System")
        processes += kernelProcess
        systemProcess = kernelProcess

        val bootThread = Thread(
            id = nextThreadId++,
            processId = kernelProcess.id,
            name = "bootstrap",
            stackBasePhysical = 0uL,
            stackSizeBytes = 0uL,
        ).also { thread ->
            thread.state = ThreadState.RUNNING
            kernelProcess.addThread(thread)
        }
        bootstrapThread = bootThread

        idleThread = createKernelThread(
            process = kernelProcess,
            name = "idle",
            entryPoint = idleThreadEntryPoint,
        )

        println(
            "ProcessManager initialized PID=${kernelProcess.id} Name=${kernelProcess.name} threads=${kernelProcess.threads.size}",
        )
    }

    fun createProcess(name: String, clone: Boolean): Process {
        val directory = if (clone) {
            KernelPageDirectory.duplicateDirectory() ?: run {
                println("ProcessManager: failed to duplicate page directory, fallback to kernel directory")
                KernelPageDirectory.getDirectory()
            }
        } else {
            KernelPageDirectory.getDirectory()
        }

        val process = Process(
            id = nextProcessId++,
            name = name,
            directory = directory,
        )
        processes += process
        return process
    }

    fun createThread(
        process: Process,
        name: String,
        entryPoint: ULong,
        argument: ULong = 0uL,
        stackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
    ): Thread? = createKernelThread(
        process = process,
        name = name,
        entryPoint = entryPoint,
        argument = argument,
        stackPages = stackPages,
    )

    fun createThreadFromContext(
        name: String,
        entryPoint: ULong,
        stackPointer: ULong,
        argument: ULong = 0uL,
    ): Thread? {
        val process = systemProcess ?: return null
        if (entryPoint == 0uL || stackPointer == 0uL) {
            return null
        }

        val thread = Thread(
            id = nextThreadId++,
            processId = process.id,
            name = name,
            stackBasePhysical = 0uL,
            stackSizeBytes = 0uL,
        )
        thread.initializeContext(entryPoint, stackPointer, argument)

        process.addThread(thread)
        Scheduler.enqueueThread(thread)
        return thread
    }

    fun getBootstrapThread(): Thread? = bootstrapThread

    fun getIdleThread(): Thread? = idleThread

    fun getSystemProcess(): Process? = systemProcess

    fun allThreads(): List<Thread> = processes.flatMap { it.threads }

    private fun createKernelProcess(name: String): Process =
        Process(
            id = nextProcessId++,
            name = name,
            directory = KernelPageDirectory.getDirectory(),
        )

    private fun createKernelThread(
        process: Process,
        name: String,
        entryPoint: ULong,
        argument: ULong = 0uL,
        stackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
    ): Thread? {
        if (!BuddyFrameAllocator.isReady && !BuddyFrameAllocator.initialize()) {
            println("ProcessManager: frame allocator unavailable for thread '$name'")
            return null
        }
        if (!Hhdm.isReady && Hhdm.initialize() == null) {
            println("ProcessManager: HHDM unavailable for thread '$name'")
            return null
        }

        val pages = if (stackPages == 0uL) DEFAULT_THREAD_STACK_PAGES else stackPages
        val stackBasePhysical = BuddyFrameAllocator.allocateFrames(pages) ?: run {
            println("ProcessManager: failed to allocate stack for thread '$name'")
            return null
        }
        val stackSizeBytes = pages * PAGE_SIZE_BYTES
        val stackTopVirtual = Hhdm.toVirtual(stackBasePhysical + stackSizeBytes).alignDown(16uL)

        val thread = Thread(
            id = nextThreadId++,
            processId = process.id,
            name = name,
            stackBasePhysical = stackBasePhysical,
            stackSizeBytes = stackSizeBytes,
        )
        thread.initializeContext(entryPoint, stackTopVirtual, argument)

        process.addThread(thread)
        Scheduler.enqueueThread(thread)
        return thread
    }
}
