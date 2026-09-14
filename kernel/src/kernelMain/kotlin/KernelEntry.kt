@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.module.Init

@CName("kernel_main")
fun kernelMain() = KernelBoot.start {
    check(FileSystemManager.mountRootfs()) { "Cannot mount root filesystem" }
    println("Kernel load done!")
    Init.setupInitProgram()
    KernelCoroutines.launchAmlEventWorker()
}
