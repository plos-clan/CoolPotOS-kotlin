@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.syscall

import KERNEL_NAME
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal object UtsSyscalls {
    private val namespace = UtsNamespace(
        sysName = "CoolPotOS",
        nodeName = "localhost",
        release = KERNEL_NAME,
        version = "v0.0.1",
        machine = "x86_64",
        domainName = "",
    )

    fun uname(regs: PtraceRegisters, process: Process): Long {
        val output = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
        return if (namespace.copyTo(output)) 0L else errno(Errno.EFAULT)
    }

    fun setHostname(regs: PtraceRegisters, process: Process): Long =
        setName(regs, process, UtsNamespace.MutableField.NODE_NAME)

    fun setDomainName(regs: PtraceRegisters, process: Process): Long =
        setName(regs, process, UtsNamespace.MutableField.DOMAIN_NAME)

    private fun setName(
        regs: PtraceRegisters,
        process: Process,
        field: UtsNamespace.MutableField,
    ): Long {
        val permitted = ProcessManager.currentThread()?.capabilities
            ?.hasEffective(CapEnum.SYS_ADMIN) == true
        if (!permitted) return errno(Errno.EPERM)

        val length = regs[PtraceRegisters.IDX_RSI]
        if (length > UtsNamespace.MAX_NAME_LENGTH.toULong()) {
            return errno(Errno.EINVAL)
        }
        val name = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
            .copyFromUser(length.toInt())
            ?: return errno(Errno.EFAULT)

        namespace.setName(field, name)
        return 0L
    }
}

internal class UtsNamespace(
    sysName: String,
    nodeName: String,
    release: String,
    version: String,
    machine: String,
    domainName: String,
) {
    private enum class Field(val abiName: String) {
        SYSTEM_NAME("sysname"),
        NODE_NAME("nodename"),
        RELEASE("release"),
        VERSION("version"),
        MACHINE("machine"),
        DOMAIN_NAME("domainname"),
        ;

        val offset: Int
            get() = ordinal * FIELD_SIZE
    }

    enum class MutableField(private val abiField: Field) {
        NODE_NAME(Field.NODE_NAME),
        DOMAIN_NAME(Field.DOMAIN_NAME),
        ;

        internal val offset: Int
            get() = abiField.offset
    }

    private val state = AtomicReference(
        encode(arrayOf(sysName, nodeName, release, version, machine, domainName)),
    )

    fun copyTo(destination: BufferDestination): Boolean {
        val snapshot = state.load()
        return destination.copyFrom(0, snapshot, 0, snapshot.size) == snapshot.size
    }

    fun setName(field: MutableField, name: ByteArray) {
        require(name.size <= MAX_NAME_LENGTH)
        while (true) {
            val current = state.load()
            val updated = current.copyOf().apply {
                fill(
                    element = 0,
                    fromIndex = field.offset,
                    toIndex = field.offset + FIELD_SIZE,
                )
                name.copyInto(this, destinationOffset = field.offset)
            }
            if (state.compareAndSet(current, updated)) return
        }
    }

    private fun encode(fields: Array<String>): ByteArray {
        require(fields.size == Field.entries.size)
        return ByteArray(NATIVE_SIZE).also { output ->
            Field.entries.forEach { field ->
                val value = fields[field.ordinal]
                require('\u0000' !in value) { "${field.abiName} must not contain NUL" }
                val encoded = value.encodeToByteArray()
                require(encoded.size <= MAX_NAME_LENGTH) {
                    "${field.abiName} must fit in $MAX_NAME_LENGTH UTF-8 bytes"
                }
                encoded.copyInto(output, destinationOffset = field.offset)
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 64
        const val FIELD_SIZE = MAX_NAME_LENGTH + 1
        val NATIVE_SIZE = FIELD_SIZE * Field.entries.size
    }
}
