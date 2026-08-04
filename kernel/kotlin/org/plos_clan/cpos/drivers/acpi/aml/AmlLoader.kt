@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.drivers.acpi.AcpiTable

private const val ACPI_SDT_HEADER_LENGTH = 36

private const val AML_ALIAS_OP = 0x06u
private const val AML_NAME_OP = 0x08u
private const val AML_BYTE_PREFIX = 0x0Au
private const val AML_WORD_PREFIX = 0x0Bu
private const val AML_DWORD_PREFIX = 0x0Cu
private const val AML_STRING_PREFIX = 0x0Du
private const val AML_QWORD_PREFIX = 0x0Eu
private const val AML_SCOPE_OP = 0x10u
private const val AML_BUFFER_OP = 0x11u
private const val AML_PACKAGE_OP = 0x12u
private const val AML_VAR_PACKAGE_OP = 0x13u
private const val AML_METHOD_OP = 0x14u
private const val AML_EXTERNAL_OP = 0x15u
private const val AML_EXT_OP_PREFIX = 0x5Bu

private const val AML_EXT_MUTEX_OP = 0x01u
private const val AML_EXT_EVENT_OP = 0x02u
private const val AML_EXT_OPERATION_REGION_OP = 0x80u
private const val AML_EXT_FIELD_OP = 0x81u
private const val AML_EXT_DEVICE_OP = 0x82u
private const val AML_EXT_PROCESSOR_OP = 0x83u
private const val AML_EXT_POWER_RESOURCE_OP = 0x84u
private const val AML_EXT_THERMAL_ZONE_OP = 0x85u
private const val AML_EXT_INDEX_FIELD_OP = 0x86u
private const val AML_EXT_BANK_FIELD_OP = 0x87u
private const val MAX_AML_STATIC_BUFFER_SIZE = 16 * 1024 * 1024
private const val MAX_AML_STATIC_PACKAGE_ELEMENTS = 65_536

internal class AmlLoader(
    private val namespace: AmlNamespace,
) {
    private var definitions = 0
    private var errors = 0

    fun load(table: AcpiTable): Boolean {
        if (table.length < ACPI_SDT_HEADER_LENGTH) {
            return false
        }
        val source = AmlPointerSource(table.pointer, table.length)
        val reader = AmlByteReader(source, ACPI_SDT_HEADER_LENGTH, table.length)
        loadTermList(reader, AmlName.ROOT)
        if (errors != 0) {
            println("AML: ${table.signature} loaded with $errors recoverable parse errors")
        }
        return true
    }

    val definitionCount: Int
        get() = definitions

    private fun loadTermList(reader: AmlByteReader, scope: AmlName) {
        while (!reader.exhausted) {
            val before = reader.position
            when (reader.peek()) {
                AML_ALIAS_OP -> loadAlias(reader, scope)
                AML_NAME_OP -> loadName(reader, scope)
                AML_SCOPE_OP -> loadScope(reader, scope)
                AML_METHOD_OP -> loadMethod(reader, scope)
                AML_EXTERNAL_OP -> skipExternal(reader)
                AML_EXT_OP_PREFIX -> loadExtended(reader, scope)
                else -> skipUnknownTerm(reader)
            }
            if (reader.position <= before) {
                errors++
                reader.skip(1)
            }
        }
    }

    private fun loadAlias(reader: AmlByteReader, scope: AmlName) {
        reader.readU8()
        val target = reader.readNamePath() ?: return parseError()
        val alias = reader.readNamePath() ?: return parseError()
        namespace.define(
            namespace.declarationName(scope, alias),
            AmlAlias(target, scope),
        )
        definitions++
    }

    private fun loadName(reader: AmlByteReader, scope: AmlName) {
        reader.readU8()
        val path = reader.readNamePath() ?: return parseError()
        val value = readDataObject(reader, scope) ?: AmlUninitialized
        namespace.define(namespace.declarationName(scope, path), value)
        definitions++
    }

    private fun loadScope(reader: AmlByteReader, scope: AmlName) {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return parseError()
        val bodyReader = reader.slice(packageLength.contentStart, packageLength.end)
            ?: return parseError()
        val path = bodyReader.readNamePath() ?: return parseError(packageLength.end, reader)
        val childScope = namespace.declarationName(scope, path)
        namespace.ensure(childScope)
        loadTermList(bodyReader, childScope)
        reader.seek(packageLength.end)
    }

    private fun loadMethod(reader: AmlByteReader, scope: AmlName) {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return parseError()
        val bodyReader = reader.slice(packageLength.contentStart, packageLength.end)
            ?: return parseError()
        val path = bodyReader.readNamePath() ?: return parseError(packageLength.end, reader)
        val flags = bodyReader.readU8() ?: return parseError(packageLength.end, reader)
        val name = namespace.declarationName(scope, path)
        namespace.define(
            name,
            AmlMethod(
                argumentCount = (flags and 0x07u).toInt(),
                serialized = (flags and 0x08u) != 0u,
                syncLevel = ((flags shr 4) and 0x0Fu).toInt(),
                source = reader.source,
                bodyStart = bodyReader.position,
                bodyEnd = packageLength.end,
                declarationScope = name.parent,
            ),
        )
        definitions++
        reader.seek(packageLength.end)
    }

    private fun skipExternal(reader: AmlByteReader) {
        reader.readU8()
        reader.readNamePath() ?: return parseError()
        reader.readU8() ?: return parseError()
        reader.readU8() ?: return parseError()
    }

    private fun loadExtended(reader: AmlByteReader, scope: AmlName) {
        reader.readU8()
        when (reader.readU8()) {
            AML_EXT_MUTEX_OP -> loadMutex(reader, scope)
            AML_EXT_EVENT_OP -> loadEvent(reader, scope)
            AML_EXT_OPERATION_REGION_OP -> loadOperationRegion(reader, scope)
            AML_EXT_FIELD_OP -> loadField(reader, scope)
            AML_EXT_DEVICE_OP -> loadNamedScope(reader, scope, AmlDevice, 0)
            AML_EXT_PROCESSOR_OP -> loadNamedScope(reader, scope, AmlProcessor, 6)
            AML_EXT_POWER_RESOURCE_OP -> loadNamedScope(reader, scope, AmlUninitialized, 3)
            AML_EXT_THERMAL_ZONE_OP -> loadNamedScope(reader, scope, AmlThermalZone, 0)
            AML_EXT_INDEX_FIELD_OP, AML_EXT_BANK_FIELD_OP -> skipPackage(reader)
            else -> parseError()
        }
    }

    private fun loadMutex(reader: AmlByteReader, scope: AmlName) {
        val path = reader.readNamePath() ?: return parseError()
        val syncFlags = reader.readU8() ?: return parseError()
        namespace.define(
            namespace.declarationName(scope, path),
            AmlMutex(syncFlags and 0x0Fu),
        )
        definitions++
    }

    private fun loadEvent(reader: AmlByteReader, scope: AmlName) {
        val path = reader.readNamePath() ?: return parseError()
        namespace.define(namespace.declarationName(scope, path), AmlEvent())
        definitions++
    }

    private fun loadOperationRegion(reader: AmlByteReader, scope: AmlName) {
        val path = reader.readNamePath() ?: return parseError()
        val spaceId = reader.readU8() ?: return parseError()
        val offset = readDataObject(reader, scope)?.staticInteger() ?: return parseError()
        val length = readDataObject(reader, scope)?.staticInteger() ?: return parseError()
        namespace.define(
            namespace.declarationName(scope, path),
            AmlOperationRegion(spaceId, offset, length, scope),
        )
        definitions++
    }

    private fun loadField(reader: AmlByteReader, scope: AmlName) {
        val packageLength = reader.readPackageLength() ?: return parseError()
        val fieldReader = reader.slice(packageLength.contentStart, packageLength.end)
            ?: return parseError()
        val regionPath = fieldReader.readNamePath()
            ?: return parseError(packageLength.end, reader)
        val flags = fieldReader.readU8() ?: return parseError(packageLength.end, reader)
        var accessType = flags and 0x0Fu
        val lockRule = (flags and 0x10u) != 0u
        val updateRule = when ((flags shr 5) and 0x03u) {
            1u -> AmlFieldUpdateRule.WRITE_ONES
            2u -> AmlFieldUpdateRule.WRITE_ZEROES
            else -> AmlFieldUpdateRule.PRESERVE
        }
        var bitOffset = 0uL

        while (!fieldReader.exhausted) {
            when (fieldReader.peek()) {
                0x00u -> {
                    fieldReader.readU8()
                    bitOffset += fieldReader.readFieldLength() ?: break
                }
                0x01u -> {
                    fieldReader.readU8()
                    accessType = fieldReader.readU8() ?: break
                    fieldReader.readU8() ?: break
                }
                0x02u -> {
                    fieldReader.readU8()
                    if (fieldReader.peek()?.let(::isNameStringLead) == true) {
                        fieldReader.readNamePath()
                    } else {
                        readDataObject(fieldReader, scope)
                    }
                }
                0x03u -> {
                    fieldReader.readU8()
                    accessType = fieldReader.readU8() ?: break
                    fieldReader.readU8() ?: break
                    fieldReader.readU8() ?: break
                }
                else -> {
                    val segment = fieldReader.readNameSegment() ?: break
                    val bitLength = fieldReader.readFieldLength() ?: break
                    namespace.define(
                        scope.child(segment),
                        AmlFieldUnit(
                            regionName = regionPath,
                            declarationScope = scope,
                            bitOffset = bitOffset,
                            bitLength = bitLength,
                            accessType = accessType,
                            lockRule = lockRule,
                            updateRule = updateRule,
                        ),
                    )
                    bitOffset += bitLength
                    definitions++
                }
            }
        }
        reader.seek(packageLength.end)
    }

    private fun loadNamedScope(
        reader: AmlByteReader,
        scope: AmlName,
        value: AmlObject,
        fixedHeaderBytes: Int,
    ) {
        val packageLength = reader.readPackageLength() ?: return parseError()
        val bodyReader = reader.slice(packageLength.contentStart, packageLength.end)
            ?: return parseError()
        val path = bodyReader.readNamePath() ?: return parseError(packageLength.end, reader)
        if (!bodyReader.skip(fixedHeaderBytes)) {
            return parseError(packageLength.end, reader)
        }
        val childScope = namespace.declarationName(scope, path)
        namespace.define(childScope, value)
        definitions++
        loadTermList(bodyReader, childScope)
        reader.seek(packageLength.end)
    }

    private fun readDataObject(reader: AmlByteReader, scope: AmlName): AmlObject? =
        when (reader.peek()) {
            0x00u -> reader.readU8()?.let { AmlInteger(0uL) }
            0x01u -> reader.readU8()?.let { AmlInteger(1uL) }
            0xFFu -> reader.readU8()?.let { AmlInteger(ULong.MAX_VALUE) }
            AML_BYTE_PREFIX -> reader.readU8()?.let { reader.readU8()?.toULong()?.let(::AmlInteger) }
            AML_WORD_PREFIX -> reader.readU8()?.let { reader.readU16()?.toULong()?.let(::AmlInteger) }
            AML_DWORD_PREFIX -> reader.readU8()?.let { reader.readU32()?.toULong()?.let(::AmlInteger) }
            AML_QWORD_PREFIX -> reader.readU8()?.let { reader.readU64()?.let(::AmlInteger) }
            AML_STRING_PREFIX -> {
                reader.readU8()
                reader.readNullTerminatedAscii()?.let(::AmlString)
            }
            AML_BUFFER_OP -> readBuffer(reader, scope)
            AML_PACKAGE_OP -> readPackage(reader, scope, variableCount = false)
            AML_VAR_PACKAGE_OP -> readPackage(reader, scope, variableCount = true)
            else -> if (reader.peek()?.let(::isNameStringLead) == true) {
                reader.readNamePath()?.let { AmlAlias(it, scope) }
            } else {
                skipUnknownTerm(reader)
                null
            }
        }

    private fun readBuffer(reader: AmlByteReader, scope: AmlName): AmlObject? {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val requestedSizeValue = readDataObject(body, scope)?.staticInteger()
        if (requestedSizeValue == null || requestedSizeValue > MAX_AML_STATIC_BUFFER_SIZE.toULong()) {
            reader.seek(packageLength.end)
            return null
        }
        val requestedSize = requestedSizeValue.toInt()
        val availableSize = body.remaining
        val data = body.readBytes(minOf(requestedSize.coerceAtLeast(0), availableSize)) ?: return null
        reader.seek(packageLength.end)
        return AmlBuffer(
            if (requestedSize > data.size) data.copyOf(requestedSize) else data,
        )
    }

    private fun readPackage(
        reader: AmlByteReader,
        scope: AmlName,
        variableCount: Boolean,
    ): AmlObject? {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val countValue = if (variableCount) {
            readDataObject(body, scope)?.staticInteger()
        } else {
            body.readU8()?.toULong()
        }
        if (countValue == null || countValue > MAX_AML_STATIC_PACKAGE_ELEMENTS.toULong()) {
            reader.seek(packageLength.end)
            return null
        }
        val count = countValue.toInt()
        val elements = mutableListOf<AmlObject>()
        repeat(count.coerceAtLeast(0)) {
            if (body.exhausted) {
                elements += AmlUninitialized
            } else {
                elements += readDataObject(body, scope) ?: AmlUninitialized
            }
        }
        reader.seek(packageLength.end)
        return AmlPackage(elements)
    }

    private fun skipPackage(reader: AmlByteReader) {
        val length = reader.readPackageLength() ?: return parseError()
        reader.seek(length.end)
    }

    private fun skipUnknownTerm(reader: AmlByteReader) {
        when (reader.peek()) {
            AML_BUFFER_OP, AML_PACKAGE_OP, AML_VAR_PACKAGE_OP, AML_SCOPE_OP, AML_METHOD_OP -> {
                reader.readU8()
                skipPackage(reader)
            }
            AML_BYTE_PREFIX -> reader.skip(2)
            AML_WORD_PREFIX -> reader.skip(3)
            AML_DWORD_PREFIX -> reader.skip(5)
            AML_QWORD_PREFIX -> reader.skip(9)
            AML_STRING_PREFIX -> {
                reader.readU8()
                reader.readNullTerminatedAscii()
            }
            else -> if (reader.peek()?.let(::isNameStringLead) == true) {
                reader.readNamePath()
            } else {
                reader.skip(1)
            }
        }
    }

    private fun AmlObject.staticInteger(visited: MutableSet<AmlName> = mutableSetOf()): ULong? =
        when (this) {
            is AmlInteger -> value
            is AmlBuffer -> integerValue()
            is AmlString -> integerValue()
            is AmlAlias -> {
                val target = namespace.resolve(declarationScope, target) ?: return null
                if (!visited.add(target.name)) return null
                target.value.staticInteger(visited)
            }
            else -> null
        }

    private fun parseError() {
        errors++
    }

    private fun parseError(end: Int, reader: AmlByteReader) {
        errors++
        reader.seek(end)
    }
}
