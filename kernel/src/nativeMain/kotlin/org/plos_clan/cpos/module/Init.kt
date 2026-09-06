package org.plos_clan.cpos.module

import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.module.elf.ElfLoader
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.utils.Cmdline

object Init {
    private fun initializeStdio(process: Process): Boolean {
        val context = FileSystemManager.kernelContext ?: return false

        val console = when (
            val result = FileSystemManager.vfs.open(
                caller = process.vfsOperationContext,
                context = context,
                pathname = VfsPathname.fromString("/dev/console"),
                options = OpenOptions(access = AccessMode.READ_WRITE),
            )
        ) {
            is VfsResult.Ok -> result.value

            is VfsResult.Err -> {
                println("Init: cannot open controlling terminal: ${result.error}")
                return false
            }
        }

        if (!process.fdTable.installExact(0, console, 0U)) {
            console.release()
            return false
        }

        val caller = process.vfsOperationContext
        if (process.fdTable.duplicateTo(caller, 0, 1) is VfsResult.Err ||
            process.fdTable.duplicateTo(caller, 0, 2) is VfsResult.Err
        ) {
            process.fdTable.close(caller, 0)
            process.fdTable.close(caller, 1)
            process.fdTable.close(caller, 2)
            return false
        }

        if (!TtyManager.attachProcessToConsole(process)) {
            process.fdTable.close(caller, 0)
            process.fdTable.close(caller, 1)
            process.fdTable.close(caller, 2)
            return false
        }

        return true
    }

    fun setupInitProgram() {
        val rdinit = Cmdline["rdinit"] ?: "/init"
        val process = ProcessManager.createUserProcess(rdinit, pid = 1)
        val image = when (val result = ElfLoader.loadProcess(
            path = rdinit,
            process = process,
            arguments = listOf(rdinit),
            environment = listOf(
                "PWD=/",
                "HOME=/root",
                "TERM=${TtyManager.terminalType}",
                "PATH=/bin:/sbin:/usr/bin:/usr/sbin",
            ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("Init: cannot load executable $rdinit: ${result.error}")
                return
            }
        }
        process.credentials.commitExec(image.execution)
        process.dumpable = !image.execution.privileged
        process.installExecutable(image.executablePath, image.arguments)

        initializeStdio(process)

        val thread = when (val result = ProcessManager.createUserThread(
            process = process,
            entryPoint = image.entryPoint,
            stackPointer = image.stackPointer,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                ProcessManager.discardUserProcess(process)
                println("Init: cannot create user thread: ${result.error}")
                return
            }
        }
        thread.capabilities.applyExec(image.execution)
        Scheduler.enqueueThread(thread)

        println("Init: created process=${process.id} thread=${thread.id} rip=0x${image.entryPoint.toString(16)}")
    }
}
