package org.plos_clan.cpos.module

import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline

object Init {
    private fun readFile(path: String): VfsResult<ByteArray> {
        val context = FileSystemManager.kernelContext
            ?: return VfsResult.Err(VfsError.NOT_FOUND)

        val file = when (
            val result = FileSystemManager.vfs.open(
                context = context,
                pathname = VfsPathname.fromString(path),
                options = OpenOptions(access = AccessMode.READ),
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }

        try {
            val fileSize = file.inode.metadata().size
            if (fileSize > Int.MAX_VALUE.toULong()) {
                return VfsResult.Err(VfsError.FILE_TOO_LARGE)
            }

            val data = ByteArray(fileSize.toInt())
            var total = 0

            while (total < data.size) {
                val result = file.read(
                    destination = data,
                    offset = total,
                    count = data.size - total,
                )

                if (!result.isSuccess) {
                    return VfsResult.Err(result.error ?: VfsError.IO)
                }

                val count = result.bytesTransferred
                if (count == 0) {
                    return VfsResult.Err(VfsError.IO)
                }

                total += count
            }

            return VfsResult.Ok(data)
        } finally {
            file.release()
        }
    }

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

        return true
    }


    fun setupInitProgram() {
        val rdinit = Cmdline["rdinit"] ?: "/init"
        val process = ProcessManager.createUserProcess(rdinit)
        val entry: ElfLoadResult
        val imageInfo: ElfImageInfo
        val executableData: ByteArray

        when (val result = readFile(rdinit)) {
            is VfsResult.Ok -> {
                val loaded = ElfLoader.loadExecutorElf(
                    data = result.value,
                    directory = process.vma.pageDirectory,
                    process = process,
                )

                if (loaded == null) {
                    println("Cannot load ELF")
                    return
                }
                imageInfo = ElfLoader.inspect(result.value) ?: run {
                    println("Init: $rdinit is not a valid ELF64 image")
                    return
                }
                executableData = result.value
                entry = loaded
            }

            is VfsResult.Err -> {
                println("Cannot read $rdinit: ${result.error}")
                return
            }
        }

        var rip = entry.entryPoint

        val interpreter = if (entry.requiresInterpreter) {
            val executableBias =
                if (imageInfo.isPositionIndependent) DEFAULT_INTERPRETER_LOAD_BIAS else 0uL
            val executable = ElfLoader.loadInterpreterElf(
                executableData,
                directory = process.vma.pageDirectory,
                offset = executableBias,
                process = process,
            ) ?: run {
                println("Init: cannot load executable $rdinit")
                return
            }
            rip = executable.entryPoint
            executable
        } else null

        val rsp = UserStackBuilder.build(
            process, arguments = listOf(rdinit),
            environment = listOf(
                "PWD=/",
                "HOME=/root",
                "TERM=linux",
                "PATH=/bin:/sbin:/usr/bin:/usr/sbin",
            ),
            rdinit,
            executable = entry,
            interpreter,
        ) ?: run {
            println("error: cannot build user stack.")
            return
        }

        initializeStdio(process)

        val thread = ProcessManager.createUserThread(
            process = process,
            entryPoint = rip,
            stackPointer = rsp.stackPointer,
        ) ?: run {
            println("Init: cannot create user thread")
            return
        }

        println(
            "Init: created process=${process.id} thread=${thread.id} rip=0x${rip.toString(16)}"
        )
    }
}