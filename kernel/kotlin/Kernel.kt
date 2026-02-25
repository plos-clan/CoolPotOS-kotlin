import kotlinx.cinterop.*
import natives.framebuffer_request
import natives.limine_framebuffer
import org.plos_clan.cpos.driver.Acpi
import org.plos_clan.cpos.managers.ProcessManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.term.Terminal
import kotlin.experimental.ExperimentalNativeApi

private const val KERNEL_BANNER = "CoolPotOS CP_Kernel-x86_64-v0.0.1_{kotlin_edition}"

@ExperimentalNativeApi
@ExperimentalForeignApi
@CName("kernel_main")
fun kernelMain() = KernelBoot.start()

private object KernelBoot {
    @ExperimentalForeignApi
    fun start() {
        framebuffer_request.response
            ?.pointed
            ?.takeIf { it.framebuffer_count > 0u }
            ?.framebuffers
            ?.get(0)
            ?.let(::initializeTerminal)

        println("Kernel booting...")
        println(KERNEL_BANNER)
        natives.gdt_setup()
        println("Global descriptor table initialized.")
        Hhdm.initialize()
        BuddyFrameAllocator.initialize()
        KernelPageDirectory.initialize()
        Acpi.init()
        ProcessManager.initialize()
        haltForever()
    }

    @ExperimentalForeignApi
    private fun initializeTerminal(framebufferPointer: CPointer<limine_framebuffer>) {
        val framebuffer = framebufferPointer.pointed
        if (framebuffer.bpp.toInt() != 32) {
            return
        }

        val baseAddress = framebuffer.address ?: return
        val width = framebuffer.width.toInt()
        val height = framebuffer.height.toInt()
        val stride = (framebuffer.pitch / 4UL).toInt()
        Terminal.initialize(baseAddress.reinterpret(), width, height, stride)
    }
}

private fun haltForever() {
    while (true) {
    }
}
