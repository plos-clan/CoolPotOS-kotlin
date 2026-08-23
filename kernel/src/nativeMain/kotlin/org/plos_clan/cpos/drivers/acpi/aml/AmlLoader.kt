@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.drivers.acpi.AcpiTable
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ALIAS_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BREAK_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BUFFER_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BYTE_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CONTINUE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DWORD_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ELSE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXTERNAL_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_BANK_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_DEVICE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_EVENT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_INDEX_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_MUTEX_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_OPERATION_REGION_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_OP_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_POWER_RESOURCE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_PROCESSOR_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_THERMAL_ZONE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_IF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_METHOD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NAME_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOOP_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_PACKAGE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_QWORD_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_RETURN_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SCOPE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_STRING_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_VAR_PACKAGE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_WHILE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_WORD_PREFIX

private const val ACPI_SDT_HEADER_LENGTH = 36
private const val MAX_AML_STATIC_BUFFER_SIZE = 16 * 1024 * 1024
private const val MAX_AML_STATIC_PACKAGE_ELEMENTS = 65_536
private const val MAX_AML_LOADER_TERMS = 1_000_000
private const val MAX_AML_TERM_DEPTH = 256

internal enum class AmlLoadStatus {
    LOADED,
    LOADED_WITH_ERRORS,
    INVALID_TABLE,
    MALFORMED_TERM,
    BUDGET_EXHAUSTED,
}

internal data class AmlLoadResult(
    val signature: String,
    val status: AmlLoadStatus,
    val consumed: Int,
    val length: Int,
    val definitions: Int,
    val errors: Int,
    val maxDepth: Int,
) {
    val success: Boolean
        get() = status == AmlLoadStatus.LOADED || status == AmlLoadStatus.LOADED_WITH_ERRORS
}

internal class AmlLoader(
    private val namespace: AmlNamespace,
) {
    private var definitions = 0
    private var terms = 0
    private var errors = 0
    private var budgetExhausted = false
    private var maxDepth = 0
    private lateinit var termParser: AmlTermParser

    val definitionCount: Int
        get() = definitions

    fun load(table: AcpiTable): AmlLoadResult = load(
        signature = table.signature,
        source = AmlPointerSource(table.pointer, table.length),
        length = table.length,
    )

    internal fun load(
        signature: String,
        source: AmlByteSource,
        length: Int = source.size,
        bodyStart: Int = ACPI_SDT_HEADER_LENGTH,
    ): AmlLoadResult {
        val initialDefinitions = definitions
        if (length < ACPI_SDT_HEADER_LENGTH || length > source.size || bodyStart !in 0..length) {
            return AmlLoadResult(signature, AmlLoadStatus.INVALID_TABLE, 0, length, 0, 1, 0)
        }

        terms = 0
        errors = 0
        budgetExhausted = false
        maxDepth = 0
        termParser = AmlTermParser(namespace)

        val reader = AmlByteReader(source, bodyStart, length)
        val completed = loadTermList(reader, AmlName.ROOT, 0)
        maxDepth = maxOf(maxDepth, termParser.maxDepth)
        if (termParser.budgetExhausted || terms > MAX_AML_LOADER_TERMS) {
            budgetExhausted = true
            reader.seek(reader.end)
        }

        val status = when {
            budgetExhausted -> AmlLoadStatus.BUDGET_EXHAUSTED
            !completed -> AmlLoadStatus.MALFORMED_TERM
            errors != 0 -> AmlLoadStatus.LOADED_WITH_ERRORS
            else -> AmlLoadStatus.LOADED
        }
        val result = AmlLoadResult(
            signature = signature,
            status = status,
            consumed = reader.position,
            length = length,
            definitions = definitions - initialDefinitions,
            errors = errors,
            maxDepth = maxDepth,
        )
        return result
    }

    private fun loadTermList(reader: AmlByteReader, scope: AmlName, depth: Int): Boolean {
        if (depth > maxDepth) maxDepth = depth
        if (depth > MAX_AML_TERM_DEPTH) {
            budgetExhausted = true
            return false
        }

        while (!reader.exhausted) {
            if (++terms > MAX_AML_LOADER_TERMS) {
                budgetExhausted = true
                return false
            }
            if (terms % 100_000 == 0) {
                println("AML: progress terms=$terms offset=${reader.position} depth=$depth")
            }
            val before = reader.position
            val parsed = when (reader.peek()) {
                AML_ALIAS_OP -> loadAlias(reader, scope)
                AML_NAME_OP -> loadName(reader, scope)
                AML_SCOPE_OP -> loadScope(reader, scope, depth)
                AML_METHOD_OP -> loadMethod(reader, scope)
                AML_EXTERNAL_OP -> loadExternal(reader, scope)
                AML_IF_OP -> loadConditional(reader, scope, depth, true)
                AML_ELSE_OP -> loadConditional(reader, scope, depth, false)
                AML_WHILE_OP -> loadConditional(reader, scope, depth, true)
                AML_RETURN_OP -> consumeTerm(reader, scope, depth)
                AML_BREAK_OP, AML_CONTINUE_OP, AML_NOOP_OP -> reader.readU8() != null
                AML_EXT_OP_PREFIX -> loadExtended(reader, scope, depth)
                else -> consumeTerm(reader, scope, depth)
            }
            if (!parsed || reader.position <= before) {
                errors++
                return false
            }
        }
        return true
    }

    private fun loadAlias(reader: AmlByteReader, scope: AmlName): Boolean {
        reader.readU8() ?: return false
        val target = reader.readNamePath() ?: return false
        val alias = reader.readNamePath() ?: return false
        namespace.define(namespace.declarationName(scope, alias), AmlAlias(target, scope))
        definitions++
        return true
    }

    private fun loadName(reader: AmlByteReader, scope: AmlName): Boolean {
        reader.readU8() ?: return false
        val path = reader.readNamePath() ?: return false
        val value = readDataObject(reader, scope) ?: return false
        namespace.define(namespace.declarationName(scope, path), value)
        definitions++
        return true
    }

    private fun loadScope(reader: AmlByteReader, scope: AmlName, depth: Int): Boolean {
        reader.readU8() ?: return false
        val length = reader.readPackageLength() ?: return false
        val body = reader.slice(length.contentStart, length.end) ?: return false
        val path = body.readNamePath() ?: return packageFailure(reader, length.end)
        val childScope = namespace.declarationName(scope, path)
        namespace.ensure(childScope)
        val loaded = loadTermList(body, childScope, depth + 1)
        reader.seek(length.end)
        if (!loaded) errors++
        return true
    }

    private fun loadMethod(reader: AmlByteReader, scope: AmlName): Boolean {
        reader.readU8() ?: return false
        val length = reader.readPackageLength() ?: return false
        val body = reader.slice(length.contentStart, length.end) ?: return false
        val path = body.readNamePath() ?: return packageFailure(reader, length.end)
        val flags = body.readU8() ?: return packageFailure(reader, length.end)
        val name = namespace.declarationName(scope, path)
        val method = AmlMethod(
            argumentCount = (flags and 0x07u).toInt(),
            serialized = (flags and 0x08u) != 0u,
            syncLevel = ((flags shr 4) and 0x0Fu).toInt(),
            source = reader.source,
            bodyStart = body.position,
            bodyEnd = length.end,
            declarationScope = name.parent,
        )
        val node = namespace.ensure(name)
        node.value = method
        definitions++
        reader.seek(length.end)
        return true
    }

    private fun loadExternal(reader: AmlByteReader, scope: AmlName): Boolean {
        reader.readU8() ?: return false
        val path = reader.readNamePath() ?: return false
        val objectType = reader.readU8() ?: return false
        val argumentCount = reader.readU8() ?: return false
        if (objectType == 0x08u) {
            val name = namespace.declarationName(scope, path)
            val existing = namespace.find(name)
            if (existing == null || existing.value === AmlUninitialized) {
                namespace.define(name, AmlExternalMethod(argumentCount.toInt(), name.parent))
            }
            definitions++
        }
        return true
    }

    private fun loadConditional(
        reader: AmlByteReader,
        scope: AmlName,
        depth: Int,
        hasPredicate: Boolean,
    ): Boolean {
        reader.readU8() ?: return false
        val length = reader.readPackageLength() ?: return false
        val body = reader.slice(length.contentStart, length.end) ?: return false
        if (hasPredicate) {
            termParser.read(body, scope, depth + 1) ?: return packageFailure(reader, length.end)
        }
        val loaded = loadTermList(body, scope, depth + 1)
        reader.seek(length.end)
        if (!loaded) errors++
        return true
    }

    private fun loadExtended(reader: AmlByteReader, scope: AmlName, depth: Int): Boolean {
        reader.readU8() ?: return false
        return when (reader.readU8()) {
            AML_EXT_MUTEX_OP -> loadMutex(reader, scope)
            AML_EXT_EVENT_OP -> loadEvent(reader, scope)
            AML_EXT_OPERATION_REGION_OP -> loadOperationRegion(reader, scope)
            AML_EXT_FIELD_OP -> loadField(reader, scope, AmlFieldMode.Region)
            AML_EXT_INDEX_FIELD_OP -> loadField(reader, scope, AmlFieldMode.Index)
            AML_EXT_BANK_FIELD_OP -> loadField(reader, scope, AmlFieldMode.Bank)
            AML_EXT_DEVICE_OP -> loadNamedScope(reader, scope, AmlDevice, 0, depth)
            AML_EXT_PROCESSOR_OP -> loadNamedScope(reader, scope, AmlProcessor, 6, depth)
            AML_EXT_POWER_RESOURCE_OP -> loadNamedScope(reader, scope, AmlUninitialized, 3, depth)
            AML_EXT_THERMAL_ZONE_OP -> loadNamedScope(reader, scope, AmlThermalZone, 0, depth)
            else -> false
        }
    }

    private fun loadMutex(reader: AmlByteReader, scope: AmlName): Boolean {
        val path = reader.readNamePath() ?: return false
        val flags = reader.readU8() ?: return false
        namespace.define(namespace.declarationName(scope, path), AmlMutex(flags and 0x0Fu))
        definitions++
        return true
    }

    private fun loadEvent(reader: AmlByteReader, scope: AmlName): Boolean {
        val path = reader.readNamePath() ?: return false
        namespace.define(namespace.declarationName(scope, path), AmlEvent())
        definitions++
        return true
    }

    private fun loadOperationRegion(reader: AmlByteReader, scope: AmlName): Boolean {
        val path = reader.readNamePath() ?: return false
        val spaceId = reader.readU8() ?: return false
        val offset = termParser.read(reader, scope) ?: return false
        val length = termParser.read(reader, scope) ?: return false
        namespace.define(
            namespace.declarationName(scope, path),
            AmlOperationRegion(spaceId, offset, length, scope),
        )
        definitions++
        return true
    }

    private enum class AmlFieldMode { Region, Index, Bank }

    private fun loadField(reader: AmlByteReader, scope: AmlName, mode: AmlFieldMode): Boolean {
        val length = reader.readPackageLength() ?: return false
        val body = reader.slice(length.contentStart, length.end) ?: return false
        val binding = when (mode) {
            AmlFieldMode.Region -> AmlFieldBinding.Region(
                body.readNamePath() ?: return packageFailure(reader, length.end)
            )

            AmlFieldMode.Index -> {
                val index = body.readNamePath() ?: return packageFailure(reader, length.end)
                val data = body.readNamePath() ?: return packageFailure(reader, length.end)
                AmlFieldBinding.Index(index, data)
            }

            AmlFieldMode.Bank -> {
                val region = body.readNamePath() ?: return packageFailure(reader, length.end)
                val bank = body.readNamePath() ?: return packageFailure(reader, length.end)
                val value =
                    termParser.read(body, scope) ?: return packageFailure(reader, length.end)
                AmlFieldBinding.Bank(region, bank, value)
            }
        }
        val flags = body.readU8() ?: return packageFailure(reader, length.end)
        val loaded = loadFieldList(body, scope, binding, flags)
        reader.seek(length.end)
        if (!loaded) errors++
        return true
    }

    private fun loadFieldList(
        reader: AmlByteReader,
        scope: AmlName,
        binding: AmlFieldBinding,
        flags: UInt,
    ): Boolean {
        var accessType = flags and 0x0Fu
        val lockRule = (flags and 0x10u) != 0u
        val updateRule = when ((flags shr 5) and 0x03u) {
            1u -> AmlFieldUpdateRule.WRITE_ONES
            2u -> AmlFieldUpdateRule.WRITE_ZEROES
            else -> AmlFieldUpdateRule.PRESERVE
        }
        var bitOffset = 0uL
        while (!reader.exhausted) {
            val before = reader.position
            when (reader.peek()) {
                0x00u -> {
                    reader.readU8() ?: return false
                    val length = reader.readFieldLength() ?: return false
                    bitOffset = bitOffset.checkedAdd(length) ?: return false
                }

                0x01u -> {
                    reader.readU8() ?: return false
                    accessType = reader.readU8() ?: return false
                    reader.readU8() ?: return false
                }

                0x02u -> {
                    reader.readU8() ?: return false
                    if (reader.peek()?.let(::isNameStringLead) == true) {
                        reader.readNamePath() ?: return false
                    } else {
                        readDataObject(reader, scope) ?: return false
                    }
                }

                0x03u -> {
                    reader.readU8() ?: return false
                    accessType = reader.readU8() ?: return false
                    reader.readU8() ?: return false
                    reader.readU8() ?: return false
                }

                else -> {
                    val name = reader.readNameSegment() ?: return false
                    val length = reader.readFieldLength() ?: return false
                    namespace.define(
                        scope.child(name),
                        AmlFieldUnit(
                            binding,
                            scope,
                            bitOffset,
                            length,
                            accessType,
                            lockRule,
                            updateRule
                        ),
                    )
                    definitions++
                    bitOffset = bitOffset.checkedAdd(length) ?: return false
                }
            }
            if (reader.position <= before) return false
        }
        return true
    }

    private fun loadNamedScope(
        reader: AmlByteReader,
        scope: AmlName,
        value: AmlObject,
        fixedHeaderBytes: Int,
        depth: Int,
    ): Boolean {
        val length = reader.readPackageLength() ?: return false
        val body = reader.slice(length.contentStart, length.end) ?: return false
        val path = body.readNamePath() ?: return packageFailure(reader, length.end)
        if (!body.skip(fixedHeaderBytes)) return packageFailure(reader, length.end)
        val childScope = namespace.declarationName(scope, path)
        namespace.define(childScope, value)
        definitions++
        val loaded = loadTermList(body, childScope, depth + 1)
        reader.seek(length.end)
        if (!loaded) errors++
        return true
    }

    private fun readDataObject(reader: AmlByteReader, scope: AmlName): AmlObject? =
        when (reader.peek()) {
            0x00u -> reader.readU8()?.let { AmlInteger(0uL) }
            0x01u -> reader.readU8()?.let { AmlInteger(1uL) }
            0xFFu -> reader.readU8()?.let { AmlInteger(ULong.MAX_VALUE) }
            AML_BYTE_PREFIX -> prefixedInteger(reader, 1)
            AML_WORD_PREFIX -> prefixedInteger(reader, 2)
            AML_DWORD_PREFIX -> prefixedInteger(reader, 4)
            AML_QWORD_PREFIX -> prefixedInteger(reader, 8)
            AML_STRING_PREFIX -> {
                reader.readU8()
                reader.readNullTerminatedAscii()?.let(::AmlString)
            }

            AML_BUFFER_OP -> readBuffer(reader, scope)
            AML_PACKAGE_OP -> readPackage(reader, scope, false)
            AML_VAR_PACKAGE_OP -> readPackage(reader, scope, true)
            else -> termParser.read(reader, scope)?.asObject(namespace)
        }

    private fun prefixedInteger(reader: AmlByteReader, byteCount: Int): AmlObject? {
        reader.readU8() ?: return null
        val value = when (byteCount) {
            1 -> reader.readU8()?.toULong()
            2 -> reader.readU16()?.toULong()
            4 -> reader.readU32()?.toULong()
            8 -> reader.readU64()
            else -> null
        } ?: return null
        return AmlInteger(value)
    }

    private fun readBuffer(reader: AmlByteReader, scope: AmlName): AmlObject? {
        reader.readU8() ?: return null
        val length = reader.readPackageLength() ?: return null
        val body = reader.slice(length.contentStart, length.end) ?: return null
        val size = readDataObject(body, scope)?.integerValue()
        if (size == null || size > MAX_AML_STATIC_BUFFER_SIZE.toULong()) {
            reader.seek(length.end)
            return AmlUninitialized
        }
        val requested = size.toInt()
        val data = body.readBytes(minOf(requested, body.remaining)) ?: return null
        reader.seek(length.end)
        return AmlBuffer(if (data.size < requested) data.copyOf(requested) else data)
    }

    private fun readPackage(
        reader: AmlByteReader,
        scope: AmlName,
        variableCount: Boolean
    ): AmlObject? {
        reader.readU8() ?: return null
        val length = reader.readPackageLength() ?: return null
        val body = reader.slice(length.contentStart, length.end) ?: return null
        val count =
            if (variableCount) readDataObject(body, scope)?.integerValue() else body.readU8()
                ?.toULong()
        if (count == null || count > MAX_AML_STATIC_PACKAGE_ELEMENTS.toULong()) {
            reader.seek(length.end)
            return AmlUninitialized
        }
        val elements = MutableList(count.toInt()) { AmlUninitialized as AmlObject }
        for (index in elements.indices) {
            if (body.exhausted) break
            elements[index] = readDataObject(body, scope) ?: run {
                reader.seek(length.end)
                return AmlUninitialized
            }
        }
        reader.seek(length.end)
        return AmlPackage(elements)
    }

    private fun consumeTerm(reader: AmlByteReader, scope: AmlName, depth: Int): Boolean =
        termParser.read(reader, scope, depth) != null

    private fun packageFailure(reader: AmlByteReader, end: Int): Boolean {
        errors++
        reader.seek(end)
        return true
    }

    private fun ULong.checkedAdd(value: ULong): ULong? =
        if (this > ULong.MAX_VALUE - value) null else this + value
}
