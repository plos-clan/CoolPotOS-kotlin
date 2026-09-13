@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fault.SignalInterrupt
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.tasks.keys.KeyFailure
import org.plos_clan.cpos.tasks.keys.KeyStore
import org.plos_clan.cpos.tasks.keys.Keys
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters

internal object KeySyscalls {
    private enum class Command {
        GET_KEYRING_ID, JOIN_SESSION_KEYRING, UPDATE, REVOKE, CHOWN, SETPERM, DESCRIBE,
        CLEAR, LINK, UNLINK, SEARCH, READ, INSTANTIATE, NEGATE, SET_REQKEY_KEYRING,
        SET_TIMEOUT, ASSUME_AUTHORITY, GET_SECURITY, SESSION_TO_PARENT, REJECT,
        INSTANTIATE_IOV, INVALIDATE, GET_PERSISTENT, DH_COMPUTE, PKEY_QUERY,
        PKEY_ENCRYPT, PKEY_DECRYPT, PKEY_SIGN, PKEY_VERIFY, RESTRICT_KEYRING, MOVE,
        CAPABILITIES, WATCH_KEY,
    }

    fun add(regs: PtraceRegisters, process: Process): Long = execute(process) {
        val type = KeyStore.Type.from(string(regs[PtraceRegisters.IDX_RDI], 32))
        val description = string(regs[PtraceRegisters.IDX_RSI])
        val payload = payload(regs[PtraceRegisters.IDX_RDX], regs[PtraceRegisters.IDX_R10], 1_048_575)
        access { add(type, description, payload, regs[PtraceRegisters.IDX_R8].toInt()).toLong() }
    }

    fun request(regs: PtraceRegisters, process: Process): Long = execute(process) {
        val type = KeyStore.Type.from(string(regs[PtraceRegisters.IDX_RDI], 32))
        val description = string(regs[PtraceRegisters.IDX_RSI])
        val callout = regs[PtraceRegisters.IDX_RDX]
        if (callout != 0uL) string(callout)
        access { request(type, description, regs[PtraceRegisters.IDX_R10].toInt()).toLong() }
    }

    fun control(regs: PtraceRegisters, process: Process): Long = execute(process) {
        val command = Command.entries.getOrNull(regs[PtraceRegisters.IDX_RDI].toInt())
            ?: throw KeyFailure(Errno.EOPNOTSUPP)
        val arg2 = regs[PtraceRegisters.IDX_RSI]
        val arg3 = regs[PtraceRegisters.IDX_RDX]
        val arg4 = regs[PtraceRegisters.IDX_R10]
        val arg5 = regs[PtraceRegisters.IDX_R8]
        val id = arg2.toInt()
        when (command) {
            Command.GET_KEYRING_ID -> return@execute access { getId(id, arg3.toInt() != 0).toLong() }
            Command.JOIN_SESSION_KEYRING -> {
                val name = if (arg2 == 0uL) null else string(arg2, error = Errno.ENAMETOOLONG)
                return@execute access { joinSession(name).toLong() }
            }
            Command.UPDATE -> {
                val bytes = payload(arg3, arg4, 4096)
                access { update(id, bytes) }
            }
            Command.REVOKE -> access { revoke(id) }
            Command.CHOWN -> access { chown(id, arg3.toInt(), arg4.toInt()) }
            Command.SETPERM -> access { setPermissions(id, arg3.toInt()) }
            Command.DESCRIBE -> return@execute output(access { describe(id) }, arg3, arg4)
            Command.CLEAR -> access { clear(id) }
            Command.LINK -> access { link(id, arg3.toInt()) }
            Command.UNLINK -> access { unlink(id, arg3.toInt()) }
            Command.SEARCH -> {
                val type = KeyStore.Type.from(string(arg3, 32))
                val description = string(arg4)
                return@execute access { search(id, type, description, arg5.toInt()).toLong() }
            }
            Command.READ -> return@execute output(access { read(id, if (arg3 == 0uL) 0uL else arg4) }, arg3, arg4)
            Command.SET_REQKEY_KEYRING -> return@execute access { setRequestDefault(id).toLong() }
            Command.SET_TIMEOUT -> access { timeout(id, arg3.toUInt()) }
            Command.ASSUME_AUTHORITY -> if (id != 0) throw KeyFailure(if (id < 0) Errno.EINVAL else Errno.ENOKEY)
            Command.GET_SECURITY -> return@execute output(access { security(id) }, arg3, arg4)
            Command.SESSION_TO_PARENT -> {
                val parent = thread.parentThread ?: throw KeyFailure(Errno.EPERM)
                if (parent.process.id <= 1 || parent.process.isKernelProcess || parent.state == TaskState.ZOMBIE ||
                    parent.process.threads.count { it.state != TaskState.ZOMBIE } != 1
                ) throw KeyFailure(Errno.EPERM)
                Keys.sessionToParent(thread, parent)
                SignalInterrupt.request(parent)
            }
            Command.REJECT -> {
                val error = arg4.toUInt()
                if (error == 0u || error >= 4095u || error in 512u..514u || error == 516u) {
                    throw KeyFailure(Errno.EINVAL)
                }
                throw KeyFailure(Errno.EPERM)
            }
            Command.INSTANTIATE, Command.INSTANTIATE_IOV, Command.NEGATE -> throw KeyFailure(Errno.EPERM)
            Command.INVALIDATE -> access { invalidate(id) }
            Command.GET_PERSISTENT -> return@execute access {
                persistent(id, arg3.toInt(), thread.capabilities.hasEffective(CapEnum.SETUID)).toLong()
            }
            Command.RESTRICT_KEYRING -> {
                val type = if (arg3 == 0uL) null else string(arg3, 32)
                val restriction = if (arg4 == 0uL) null else string(arg4)
                access { restrict(id, type, restriction) }
            }
            Command.MOVE -> access { move(id, arg3.toInt(), arg4.toInt(), arg5.toUInt()) }
            Command.CAPABILITIES -> {
                val bytes = KeyStore.capabilities
                output(bytes, arg2, arg3, zeroTail = true)
                return@execute bytes.size.toLong()
            }
            Command.DH_COMPUTE, Command.PKEY_QUERY, Command.PKEY_ENCRYPT, Command.PKEY_DECRYPT,
            Command.PKEY_SIGN, Command.PKEY_VERIFY, Command.WATCH_KEY -> throw KeyFailure(Errno.EOPNOTSUPP)
        }
        0
    }

    private class Request(val process: Process, val thread: Thread) {
        fun <T> access(action: KeyStore.Context.() -> T): T = Keys.access(thread, action)

        fun string(address: ULong, limit: Int = 4096, error: Int = Errno.EINVAL): String {
            val memory = UserMemory(process.addressSpace, address)
            val bytes = memory.copyCStringFromUser(limit) ?: throw KeyFailure(
                if (memory.prepareRead(0, limit) == null) Errno.EFAULT else error,
            )
            return CharArray(bytes.size) { bytes[it].toInt().and(0xff).toChar() }.concatToString()
        }

        fun payload(address: ULong, length: ULong, maximum: Int): ByteArray {
            if (length > maximum.toULong()) throw KeyFailure(Errno.EINVAL)
            return UserMemory(process.addressSpace, address).copyFromUser(length.toInt())
                ?: throw KeyFailure(Errno.EFAULT)
        }

        fun output(bytes: ByteArray, address: ULong, length: ULong, zeroTail: Boolean = false): Long {
            if (length == 0uL || address == 0uL && !zeroTail) return bytes.size.toLong()
            val count = minOf(length, bytes.size.toULong()).toInt()
            if (!UserMemory(process.addressSpace, address).copyToUser(bytes, size = count)) {
                throw KeyFailure(Errno.EFAULT)
            }
            var copied = count.toULong()
            while (zeroTail && copied < length) {
                val memory = Syscall.userMemory(process, address, copied) ?: throw KeyFailure(Errno.EFAULT)
                val chunk = minOf(length - copied, Int.MAX_VALUE.toULong()).toInt()
                if (memory.fill(0, chunk, 0) != chunk) throw KeyFailure(Errno.EFAULT)
                copied += chunk.toULong()
            }
            return bytes.size.toLong()
        }
    }

    private inline fun execute(process: Process, action: Request.() -> Long): Long = try {
        val thread = ProcessManager.currentThread()
        if (thread == null) Syscall.errno(Errno.ESRCH) else Request(process, thread).action()
    } catch (failure: KeyFailure) {
        Syscall.errno(failure.errno)
    }
}
