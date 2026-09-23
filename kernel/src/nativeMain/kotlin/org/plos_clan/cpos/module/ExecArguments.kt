package org.plos_clan.cpos.module

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal class ExecArguments private constructor(
    val values: List<String>,
    val environment: List<String>,
) {
    private class Reader(private val process: Process, private var remaining: ULong) {
        fun vector(address: ULong, requireArgument: Boolean = false): VfsResult<List<String>> {
            val values = mutableListOf<String>()
            var cursor = address
            while (cursor != 0uL) {
                val memory = UserMemory(process.addressSpace, cursor)
                val encoded = memory.copyFromUser(ULong.SIZE_BYTES)
                    ?: return VfsResult.Err(VfsError.FAULT)
                val pointer = LittleEndianBuffer(encoded).readU64(0)
                if (pointer == 0uL) break
                if (remaining <= ULong.SIZE_BYTES.toULong()) return VfsResult.Err(TOO_LONG)
                val limit = minOf(STRING_LIMIT, remaining - ULong.SIZE_BYTES.toULong()).toInt()
                val string = UserMemory(process.addressSpace, pointer)
                val bytes = when (val result = string.copyCStringFromUser(limit, TOO_LONG)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return result
                }
                remaining -= (bytes.size + 1 + ULong.SIZE_BYTES).toULong()
                values.add(bytes.decodeToString())
                cursor += ULong.SIZE_BYTES.toULong()
            }
            if (requireArgument && values.isEmpty()) {
                val size = (ULong.SIZE_BYTES + 1).toULong()
                if (remaining < size) return VfsResult.Err(TOO_LONG)
                remaining -= size
                values.add("")
            }
            return VfsResult.Ok(values)
        }
    }

    companion object {
        private val STRING_LIMIT = 32uL * PAGE_SIZE_BYTES
        private val TOO_LONG = VfsError.ARGUMENT_LIST_TOO_LONG

        fun read(
            process: Process,
            arguments: ULong,
            environment: ULong,
            filenameSize: Int,
        ): VfsResult<ExecArguments> {
            val stackLimit = process.resourceLimits.get(ProcessResource.STACK).soft / 4uL
            val maximum = DEFAULT_USER_STACK_SIZE * 3uL / 4uL
            val budget = stackLimit.coerceIn(STRING_LIMIT, maximum)
            val reader = Reader(process, budget - filenameSize.toULong())
            val values = when (val result = reader.vector(arguments, requireArgument = true)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val variables = when (val result = reader.vector(environment)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return result
            }
            val decoded = ExecArguments(values, variables)
            return VfsResult.Ok(decoded)
        }
    }
}
