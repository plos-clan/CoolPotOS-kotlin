import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.FrameBuffer
import org.plos_clan.cpos.drivers.MemoryDevice
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.acpi.Acpi
import org.plos_clan.cpos.drivers.char.SerialConsole
import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.drivers.usb.Usb
import org.plos_clan.cpos.fault.ErrorHandler
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.fuse.FuseDevice
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.RuntimeMemory
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.module.Vdso
import org.plos_clan.cpos.network.NetworkStack
import org.plos_clan.cpos.syscall.Syscall
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.TaskReaper
import org.plos_clan.cpos.utils.BootIdentity
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.KernelRandom

private val KERNEL_RUNTIME = "x86_64/kotlin-${KotlinVersion.CURRENT}"
val KERNEL_NAME = "CP_Kernel-x86_64-v0.0.1_{$KERNEL_RUNTIME}"

@OptIn(ExperimentalForeignApi::class)
object KernelBoot {
    fun start(workload: () -> Unit) {
        bridge.disable_interrupt()
        bridge.set_runtime_use_mask(true)
        println("Kernel booting...")
        println("CoolPotOS $KERNEL_NAME")
        bridge.gdt_setup()
        bridge.idt_setup()
        println("Descriptor table initialized.")
        ErrorHandler.initialize()
        Hhdm.initialize()
        BuddyFrameAllocator.initialize()
        KernelPageDirectory.initialize()
        if (!RuntimeMemory.initialize()) {
            return
        }
        if (!TscClock.initialize()) {
            return
        }
        if (!Acpi.initialize()) {
            return
        }
        RealtimeClock.initialize()
        if (!Vdso.initialize()) {
            return
        }
        SMProcessor.initialize()
        SMProcessor.currentLocal().also { local ->
            Syscall.initialize(local.lapicId.toULong(), local.isBsp)
        }
        ProcessManager.initialize()
        if (!Scheduler.initialize()) {
            return
        }
        bridge.cmdline_request.response?.pointed?.cmdline?.toKString()?.let(Cmdline::parse)
        println("Kernel command line: ${Cmdline.raw}")
        KernelRandom.initialize()
        BootIdentity.initialize(KernelRandom.uuidV4())
        if (!FileSystemManager.initialize()) {
            return
        }
        FrameBuffer.initialize()
        if (!KernelCoroutines.initialize()) {
            return
        }
        TaskReaper.initialize()
        NetworkStack.initialize()
        if (!SerialConsole.install()) {
            return
        }
        if (!TtyManager.initialize()) {
            return
        }
        if (!MemoryDevice.initialize()) {
            return
        }
        if (!FuseDevice.initialize()) {
            return
        }
        Acpi.enumerateDevices()
        Usb.initialize()
        if (!Scheduler.finishBootstrap()) {
            return
        }
        bridge.enable_interrupt()
        workload()
        KernelCoroutines.runEventLoop()
    }
}
