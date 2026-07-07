import kotlinx.cinterop.*
import bridge.get_kernel_clone_thread_entry_address
import bridge.get_sys_clone_recorded_count
import bridge.get_sys_clone_stack_at
import bridge.get_sys_clone_tls_at
import org.plos_clan.cpos.drivers.Acpi
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.fault.ErrorHandler
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.utils.hex
import kotlin.experimental.ExperimentalNativeApi

private val KERNEL_RUNTIME = "x86_64/kotlin-${KotlinVersion.CURRENT}"
private val KERNEL_BANNER = "CoolPotOS Kernel v0.0.1 [$KERNEL_RUNTIME]"

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("kernel_main")
fun kernelMain() {
    bridge.disable_interrupt()
    println("Kernel booting...")
    println(KERNEL_BANNER)
    bridge.gdt_setup()
    bridge.idt_setup()
    println("Descriptor table initialized.")
    ErrorHandler.initialize()
    Hhdm.initialize()
    BuddyFrameAllocator.initialize()
    KernelPageDirectory.initialize()
    if (!Acpi.initialize()) {
        return
    }
    IrqController.initialize()
    ProcessManager.initialize()
    startCapturedCloneThreads()
    Scheduler.initialize()
    Acpi.enumerateDevices()
    println("Kernel load done!")
    Scheduler.enableScheduler()
    bridge.enable_interrupt()
    while (true) {}
}

@ExperimentalForeignApi
private fun startCapturedCloneThreads() {
    val entryPoint = get_kernel_clone_thread_entry_address()
    val threadCount = get_sys_clone_recorded_count()
    var index = 0uL
    while (index < threadCount) {
        val stack = get_sys_clone_stack_at(index)
        val tls = get_sys_clone_tls_at(index)
        val thread = ProcessManager.createThreadFromContext(
            name = "runtime-thread-$index",
            entryPoint = entryPoint,
            stackPointer = stack,
            fsBase = tls,
        )
        if (thread != null) {
            println(
                "runtime-thread[$index] loaded tid=${thread.id} stack=${stack.hex()} tls=${tls.hex()}",
            )
        }
        index++
    }
}
