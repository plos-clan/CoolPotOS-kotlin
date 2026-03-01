import bridge.framebuffer_request
import bridge.limine_framebuffer
import kotlinx.cinterop.*
import org.plos_clan.cpos.drivers.Acpi
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.fault.ErrorHandler
import org.plos_clan.cpos.drivers.term.Terminal
import kotlin.experimental.ExperimentalNativeApi

private val KERNEL_RUNTIME = "x86_64/kotlin-${KotlinVersion.CURRENT}"
private val KERNEL_BANNER = "CoolPotOS Kernel v0.0.1 [$KERNEL_RUNTIME]"
private const val SUPPORTED_FRAMEBUFFER_BPP = 32

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("kernel_main")
fun kernelMain() {
    initializeTerminal()
    println("Kernel booting...")
    println(KERNEL_BANNER)
    bridge.gdt_setup()
    bridge.idt_setup()
    println("Descriptor table initialized.")
    ErrorHandler.initialize()
    Hhdm.initialize()
    BuddyFrameAllocator.initialize()
    KernelPageDirectory.initialize()
    Acpi.initialize()
    ProcessManager.initialize()
    println("Kernel load done!")
    while (true) {}
}

@ExperimentalForeignApi
private fun initializeTerminal() {
    framebuffer_request.response
        ?.pointed
        ?.takeIf { it.framebuffer_count > 0u }
        ?.framebuffers
        ?.get(0)
        ?.let(::initializeTerminal)
}

@ExperimentalForeignApi
private fun initializeTerminal(framebufferPointer: CPointer<limine_framebuffer>) {
    val framebuffer = framebufferPointer.pointed
    if (framebuffer.bpp.toInt() != SUPPORTED_FRAMEBUFFER_BPP) {
        return
    }

    val baseAddress = framebuffer.address ?: return
    Terminal.initialize(
        pixels = baseAddress.reinterpret(),
        width = framebuffer.width.toInt(),
        height = framebuffer.height.toInt(),
        stride = (framebuffer.pitch / UInt.SIZE_BYTES.toULong()).toInt(),
    )
}
