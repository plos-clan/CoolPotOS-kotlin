package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fault.IrqAction
import org.plos_clan.cpos.fault.IrqController
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.RuntimeMemory
import org.plos_clan.cpos.tasks.SMProcessor

object MemoryInfoFile : ProcFSRender {
    override fun render(): ByteArray {
        RuntimeMemory.reclaim(ULong.MAX_VALUE)
        val physical = BuddyFrameAllocator.statistics()
        val cache = PageCache.statistics()
        val gc = RuntimeMemory.lastCollectionStatistics()
        val reclaimable = minOf(
            cache.reclaimableBytes,
            physical.totalBytes - physical.freeBytes,
        )
        val available = physical.freeBytes + reclaimable

        return buildString {
            appendMetric("MemTotal", physical.totalBytes / KIBIBYTE, "kB")
            appendMetric("MemFree", physical.freeBytes / KIBIBYTE, "kB")
            appendMetric("MemAvailable", available / KIBIBYTE, "kB")
            appendMetric("Buffers", 0uL, "kB")
            appendMetric("Cached", cache.cachedBytes / KIBIBYTE, "kB")
            appendMetric("SwapCached", 0uL, "kB")
            appendMetric("SwapTotal", 0uL, "kB")
            appendMetric("SwapFree", 0uL, "kB")
            appendMetric("KotlinHeapBeforeGC", (gc?.heapBeforeBytes ?: 0uL) / KIBIBYTE, "kB")
            appendMetric("KotlinHeapAfterGC", (gc?.heapAfterBytes ?: 0uL) / KIBIBYTE, "kB")
            appendMetric("KotlinGCReclaimed", (gc?.reclaimedBytes ?: 0uL) / KIBIBYTE, "kB")
            appendMetric("KotlinGCEpoch", gc?.epoch ?: 0uL)
            appendMetric("KotlinGCRoots", gc?.roots ?: 0uL)
            appendMetric("KotlinGCMarked", gc?.markedObjects ?: 0uL)
            appendMetric("KotlinGCSwept", gc?.sweptObjects ?: 0uL)
            appendMetric("KotlinGCKept", gc?.keptObjects ?: 0uL)
            appendMetric("KotlinGCPause", (gc?.pauseNanoseconds ?: 0uL) / 1_000uL, "us")
            appendMetric("KotlinGCDuration", (gc?.durationNanoseconds ?: 0uL) / 1_000uL, "us")
        }.encodeToByteArray()
    }

    private fun StringBuilder.appendMetric(name: String, value: ULong, unit: String? = null) {
        append(name).append(":\t").append(value)
        if (unit != null) append(' ').append(unit)
        append('\n')
    }
}

object InterruptsFile : ProcFSRender {
    private fun StringBuilder.appendCpuHeader(
        cpuCount: Int
    ) {
        append("           ")

        for (cpu in 0 until cpuCount) {
            append("CPU$cpu".padEnd(11))
        }

        append('\n')
    }

    private fun StringBuilder.renderInterrupts(cpuCount: Int, actions: Array<IrqAction?>) {
        for (action in actions) {
            action ?: continue
            append("${action.irq}:".padStart(4))
            for (cpu in 0 until cpuCount) {
                append(
                    action.cpuCount[cpu]
                        .toString()
                        .padStart(11)
                )
            }
            append("   ")
            append(action.type.displayName.padEnd(10))
            append(' ')
            append(action.irq)
            append(if (action.levelTriggered) "-level" else "-edge")
            append(' ')
            append(action.name)
            append('\n')
        }
    }

    override fun render() : ByteArray {
        return buildString {
            appendCpuHeader(SMProcessor.cpu_count.toInt())
            renderInterrupts(SMProcessor.cpu_count.toInt(), IrqController.snapshotActions())
        }.encodeToByteArray()
    }
}

object CpuInfo : ProcFSRender {
    override fun render(): ByteArray {
        return buildString {
            SMProcessor.getAllLocalInfo().forEachIndexed { processor, local ->
                appendLine("processor\t: $processor")
                appendLine("vendor_id\t: ${local.vendor}")
                appendLine("model name\t: ${local.modelName}")

                append("address sizes\t: ")
                append(local.physical)
                append(" bits physical, ")
                append(local.virtual)
                appendLine(" bits virtual")

                append("flags\t\t: ")
                appendLine(local.features)

                appendLine()
            }
        }.encodeToByteArray()
    }
}
