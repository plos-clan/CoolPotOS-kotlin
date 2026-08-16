package org.plos_clan.cpos.module

import org.plos_clan.cpos.drivers.char.TtyManager
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline

object Init {
    private fun initializeStdio(process: Process): Boolean {
        val context = FileSystemManager.kernelContext ?: return false

        val console = when (
            val result = FileSystemManager.vfs.open(
                context = context,
                pathname = VfsPathname.fromString("/dev/tty0"),
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

        if (!process.fdTable.dup2(0, 1) || !process.fdTable.dup2(0, 2)) {
            process.fdTable.close(0)
            process.fdTable.close(1)
            process.fdTable.close(2)
            return false
        }

        if (!TtyManager.attachProcessToVT(0, process)) {
            process.fdTable.close(0)
            process.fdTable.close(1)
            process.fdTable.close(2)
            return false
        }

        return true
    }

    fun setupInitProgram() {
        val rdinit = Cmdline["rdinit"] ?: "/init"
        val process = ProcessManager.createUserProcess(rdinit)
        val image = when (val result = ElfLoader.loadProcess(
            path = rdinit,
            process = process,
            arguments = listOf(rdinit),
            environment = listOf(
                "PWD=/",
                "HOME=/root",
                "TERM=linux",
                "PATH=/bin:/sbin:/usr/bin:/usr/sbin",
            ),
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("Init: cannot load executable $rdinit: ${result.error}")
                return
            }
        }
        process.installExecutable(rdinit, listOf(rdinit))

        initializeStdio(process)

        val thread = ProcessManager.createUserThread(
            process = process,
            entryPoint = image.entryPoint,
            stackPointer = image.stackPointer,
        ) ?: run {
            println("Init: cannot create user thread")
            return
        }

        println("Init: created process=${process.id} thread=${thread.id} rip=0x${image.entryPoint.toString(16)}")
    }
}
