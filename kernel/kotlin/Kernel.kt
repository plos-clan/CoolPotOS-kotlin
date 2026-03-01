import bridge.framebuffer_request
import bridge.gdt_setup
import bridge.idt_setup
import bridge.limine_framebuffer
import kotlinx.cinterop.*
import org.plos_clan.cpos.driver.Acpi
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.fault.ErrorHandler
import org.plos_clan.cpos.term.Terminal
import kotlin.experimental.ExperimentalNativeApi

private const val KERNEL_BANNER = "CoolPotOS CP_Kernel-x86_64-v0.0.1_{kotlin_edition}"
private const val SUPPORTED_FRAMEBUFFER_BPP = 32

@ExperimentalNativeApi
@ExperimentalForeignApi
@CName("kernel_main")
fun kernelMain() = KernelBoot.start()

private object KernelBoot {
    @ExperimentalForeignApi
    fun start() {
        initializeTerminal()

        println("Kernel booting...")
        println(KERNEL_BANNER)
        gdt_setup()
        idt_setup()
        println("Descriptor table initialized.")
        ErrorHandler.initialize()
        Hhdm.initialize()
        BuddyFrameAllocator.initialize()
        KernelPageDirectory.initialize()
        Acpi.init()
        ProcessManager.initialize()
        println("Kernel load done!")
        haltForever()
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
}

private fun haltForever(): Nothing {
    while (true) {}
}
