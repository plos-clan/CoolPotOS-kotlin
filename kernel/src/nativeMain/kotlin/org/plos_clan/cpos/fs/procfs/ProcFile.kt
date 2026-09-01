package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.drivers.char.tty.TtyManager
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.addressspace.FileRegionBacking
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.ProcessState
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.hasBit

val Process.comm: String
    get() = name.substringAfterLast('/').ifEmpty { name }.take(MAX_COMM_LENGTH)

val Process.isRunnable: Boolean
    get() = state.canReceiveSignals && threads.any {
        it.state == TaskState.READY || it.state == TaskState.RUNNING
    }

enum class ProcessFile(val fileName: String) {
    COMMAND_LINE("cmdline"),
    COMMAND_NAME("comm"),
    MEMORY("statm"),
    STATISTICS("stat"),
    STATUS("status"),
    MAPS("maps"),
    MOUNTS("mounts"),
    ;

    fun render(process: Process): ByteArray = when (this) {
        COMMAND_LINE -> process.commandLine.copyOf()
        COMMAND_NAME -> "${process.comm}\n".encodeToByteArray()
        MEMORY -> {
            val pages = process.addressSpace.used / PAGE_SIZE_BYTES
            "$pages 0 0 0 0 0 0\n".encodeToByteArray()
        }

        STATISTICS -> process.stat().encodeToByteArray()
        STATUS -> process.status().encodeToByteArray()
        MAPS -> process.maps().encodeToByteArray()
        MOUNTS -> MountsFile.render(process)
    }
}

fun Process.stat(): String {
    val terminal = TtyManager.processTerminal(this)
    val membership = this.membership
    val fields = buildList {
        add(stateCode().toString())
        add(parentId.toString())
        add(membership.processGroupId.toString())
        add(membership.sessionId.toString())
        add((terminal?.deviceNumber ?: 0uL).toString())
        add((terminal?.foregroundProcessGroup ?: -1).toString())
        repeat(9) { add("0") }
        add("20") // priority
        add("0") // nice
        add(threads.count { it.state != TaskState.ZOMBIE }.toString())
        add("0") // itrealvalue
        add(startTimeTicks.toString())
        add(addressSpace.used.toString())
        add("0")
        add(resourceLimits.get(ProcessResource.RSS).soft.toString())
        repeat(27) { add("0") }
    }
    return "$id ($comm) ${fields.joinToString(" ")}\n"
}

fun Process.status(): String {
    val (stateCode, stateName) = stateDescription()
    val thread = threads.firstOrNull { it.state != TaskState.ZOMBIE }
    val blockedSignals = thread?.signals?.mask ?: 0uL
    val pendingSignals = thread?.signals?.pending?.mask ?: 0uL
    val sharedPendingSignals = signals.pending.mask
    val (ignoredSignals, caughtSignals) = signals.actionMasks()
    val capabilities = thread?.capabilities
    val userIds = credentials.userIds
    val groupIds = credentials.groupIds
    fun ULong.signalMask() = toString(16).padStart(16, '0')
    return buildString {
        append("Name:\t").append(comm).append('\n')
        append("State:\t").append(stateCode).append(" (").append(stateName).append(")\n")
        append("Tgid:\t").append(id).append('\n')
        append("Pid:\t").append(id).append('\n')
        append("PPid:\t").append(parentId).append('\n')
        append("Uid:\t").append(userIds.real).append('\t').append(userIds.effective).append('\t')
            .append(userIds.saved).append('\t').append(userIds.filesystem).append('\n')
        append("Gid:\t").append(groupIds.real).append('\t').append(groupIds.effective).append('\t')
            .append(groupIds.saved).append('\t').append(groupIds.filesystem).append('\n')
        append("FDSize:\t").append(resourceLimits.get(ProcessResource.OPEN_FILES).soft)
            .append('\n')
        append("Groups:\t").append(credentials.supplementaryGroups.joinToString(" ")).append('\n')
        append("VmSize:\t").append(addressSpace.used / KIBIBYTE).append(" kB\n")
        append("VmRSS:\t0 kB\n")
        append("Threads:\t").append(threads.count { it.state != TaskState.ZOMBIE }).append('\n')
        append("SigPnd:\t").append(pendingSignals.signalMask()).append('\n')
        append("ShdPnd:\t").append(sharedPendingSignals.signalMask()).append('\n')
        append("SigBlk:\t").append(blockedSignals.signalMask()).append('\n')
        append("SigIgn:\t").append(ignoredSignals.signalMask()).append('\n')
        append("SigCgt:\t").append(caughtSignals.signalMask()).append('\n')
        append("CapInh:\t").append((capabilities?.inheritable ?: 0uL).signalMask()).append('\n')
        append("CapPrm:\t").append((capabilities?.permitted ?: 0uL).signalMask()).append('\n')
        append("CapEff:\t").append((capabilities?.effective ?: 0uL).signalMask()).append('\n')
        append("CapBnd:\t").append((capabilities?.bounding ?: 0uL).signalMask()).append('\n')
        append("CapAmb:\t").append((capabilities?.ambient ?: 0uL).signalMask()).append('\n')
        append("NoNewPrivs:\t").append(if (capabilities?.noNewPrivileges == true) 1 else 0)
            .append('\n')
    }
}

private fun Process.stateCode(): Char = stateDescription().first

private fun Process.stateDescription(): Pair<Char, String> = when {
    state == ProcessState.DEAD -> 'X' to "dead"
    state == ProcessState.ZOMBIE -> 'Z' to "zombie"
    state == ProcessState.STOPPED -> 'T' to "stopped"
    threads.any { it.state == TaskState.RUNNING } -> 'R' to "running"
    threads.any { it.state == TaskState.READY } -> 'R' to "runnable"
    else -> 'S' to "sleeping"
}

fun Process.maps(): String {
    val vds = this.addressSpace.snapshotRegions()
    val process = this
    return buildString {
        vds.forEach { region ->
            append(
                "${region.start.toString(16).padStart(12, '0')}-"
            )
            append(
                "${
                    region.end.toString(16).padStart(12, '0')
                } "
            )

            // 权限
            append(
                if (region.access.hasBit(
                        MEMORY_REGION_READABLE.toInt()
                    )
                ) "r" else "-"
            )
            append(
                if (region.access.hasBit(
                        MEMORY_REGION_WRITABLE.toInt()
                    )
                ) "w" else "-"
            )
            append(
                if (region.access.hasBit(
                        MEMORY_REGION_EXECUTABLE.toInt()
                    )
                ) "x" else "-"
            )
            append(
                if (region.shared) "s" else "p"
            )

            // 文件与其他映射
            when (region.type) {
                MemoryRegionType.FILE, MemoryRegionType.IMAGE -> {
                    val file = (region.backing as FileRegionBacking).file

                    val path = when (val res = FileSystemManager.vfs.absolutePath(
                        context = process.getFSContext(),
                        initial = file.path,
                    )) {
                        is VfsResult.Ok -> res.value.decodeToString()
                        is VfsResult.Err -> ""
                    }
                    append(
                        " ${file.offset.toString(16).padStart(8, '0')} 00:00 ${
                            file.inode.id.value.toString().padStart(7, '0')
                        } $path"
                    ) //TODO: 需要实现 st_dev
                }

                else -> append(" 00000000 00:00 0       ${region.name ?: ""}")
            }
            append('\n')
        }
    }
}
