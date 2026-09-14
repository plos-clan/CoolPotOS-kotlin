package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ADD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_AND_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ARG0_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ARG6_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BREAK_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BUFFER_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_BYTE_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CONCAT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CONCAT_RES_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CONTINUE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_COPY_OBJECT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_BIT_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_BYTE_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_DWORD_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_QWORD_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_CREATE_WORD_FIELD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DECREMENT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DEREF_OF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DIVIDE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DWORD_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ELSE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXTERNAL_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_ACQUIRE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_COND_REF_OF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_DEBUG_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_OP_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_RELEASE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_REVISION_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_SLEEP_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_STALL_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_EXT_TIMER_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_IF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_INCREMENT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_INDEX_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_LAND_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_LLESS_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_LNOT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_LOCAL0_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_LOCAL7_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_MOD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_MULTIPLY_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NAND_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOOP_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOR_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOTIFY_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_OBJECT_TYPE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_OR_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_PACKAGE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_QWORD_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_REF_OF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_RETURN_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SHIFT_LEFT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SHIFT_RIGHT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SIZE_OF_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_STORE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_STRING_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SUBTRACT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_TO_BUFFER_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_TO_INTEGER_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_VAR_PACKAGE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_WHILE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_WORD_PREFIX
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_XOR_OP

private const val MAX_AML_LOAD_OPERATIONS = 1_000_000

internal class AmlTermParser(
    private val namespace: AmlNamespace,
) {
    private var operations = 0

    var maxDepth: Int = 0
        private set

    var budgetExhausted: Boolean = false
        private set

    fun read(reader: AmlByteReader, scope: AmlName, depth: Int = 0): AmlTermArg? {
        if (depth > maxDepth) maxDepth = depth
        if (++operations > MAX_AML_LOAD_OPERATIONS) {
            budgetExhausted = true
            return null
        }
        if (depth > MAX_AML_TERM_DEPTH) {
            budgetExhausted = true
            return null
        }

        val start = reader.position
        val opcode = reader.peek() ?: return null
        val result = when {
            opcode == 0x00u -> constant(reader, 0uL)
            opcode == 0x01u -> constant(reader, 1uL)
            opcode == 0xFFu -> constant(reader, ULong.MAX_VALUE)
            opcode == AML_BYTE_PREFIX -> prefixedInteger(reader, 1)
            opcode == AML_WORD_PREFIX -> prefixedInteger(reader, 2)
            opcode == AML_DWORD_PREFIX -> prefixedInteger(reader, 4)
            opcode == AML_QWORD_PREFIX -> prefixedInteger(reader, 8)
            opcode == AML_STRING_PREFIX -> string(reader)
            opcode == AML_BUFFER_OP || opcode == AML_PACKAGE_OP || opcode == AML_VAR_PACKAGE_OP ->
                packageTerm(reader, opcode)
            opcode in AML_LOCAL0_OP..AML_LOCAL7_OP || opcode in AML_ARG0_OP..AML_ARG6_OP -> {
                reader.readU8()?.let { AmlTermArg.Deferred(opcode, start, reader.position) }
            }
            opcode == AML_STORE_OP -> binaryTarget(reader, opcode, depth, scope, operandCount = 1)
            opcode == AML_REF_OF_OP -> targetOperation(reader, opcode)
            opcode == AML_ADD_OP || opcode == AML_SUBTRACT_OP ||
                opcode == AML_MULTIPLY_OP ||
                opcode == AML_SHIFT_LEFT_OP || opcode == AML_SHIFT_RIGHT_OP ||
                opcode == AML_AND_OP || opcode == AML_NAND_OP || opcode == AML_OR_OP ||
                opcode == AML_NOR_OP || opcode == AML_XOR_OP || opcode == AML_CONCAT_OP ||
                opcode == AML_CONCAT_RES_OP || opcode == AML_MOD_OP ->
                binaryTarget(reader, opcode, depth, scope)
            opcode == AML_DIVIDE_OP -> divide(reader, opcode, depth, scope)
            opcode == AML_INCREMENT_OP || opcode == AML_DECREMENT_OP -> targetOperation(reader, opcode)
            opcode == AML_DEREF_OF_OP -> unaryOperation(reader, opcode, depth, scope)
            opcode == AML_SIZE_OF_OP || opcode == AML_OBJECT_TYPE_OP ->
                unaryOperation(reader, opcode, depth, scope)
            opcode == AML_NOT_OP -> unaryTarget(reader, opcode, depth, scope)
            opcode == AML_LNOT_OP -> unaryOperation(reader, opcode, depth, scope)
            opcode in AML_LAND_OP..AML_LLESS_OP -> binaryOperation(reader, opcode, depth, scope)
            opcode == AML_TO_BUFFER_OP || opcode == AML_TO_INTEGER_OP ->
                binaryTarget(reader, opcode, depth, scope, operandCount = 1)
            opcode == AML_COPY_OBJECT_OP -> binaryTarget(reader, opcode, depth, scope, operandCount = 1)
            opcode == AML_NOTIFY_OP -> notify(reader, start, depth, scope)
            opcode == AML_INDEX_OP -> index(reader, start, depth, scope)
            opcode == AML_IF_OP || opcode == AML_WHILE_OP -> controlPackage(reader, opcode, scope, depth)
            opcode == AML_ELSE_OP -> plainPackage(reader, opcode)
            opcode == AML_EXTERNAL_OP -> external(reader, start)
            opcode == AML_RETURN_OP -> {
                reader.readU8()
                read(reader, scope, depth + 1)?.let {
                    AmlTermArg.Deferred(opcode, start, reader.position)
                }
            }
            opcode == AML_NOOP_OP || opcode == AML_BREAK_OP || opcode == AML_CONTINUE_OP -> {
                reader.readU8()?.let { AmlTermArg.Deferred(opcode, start, reader.position) }
            }
            opcode == AML_CREATE_BYTE_FIELD_OP || opcode == AML_CREATE_BIT_FIELD_OP ||
                opcode == AML_CREATE_DWORD_FIELD_OP || opcode == AML_CREATE_WORD_FIELD_OP ||
                opcode == AML_CREATE_QWORD_FIELD_OP ->
                createField(reader, opcode, depth, scope, operandCount = 2)
            opcode == AML_CREATE_FIELD_OP -> createField(reader, opcode, depth, scope, operandCount = 3)
            opcode == AML_EXT_OP_PREFIX -> extended(reader, start, depth, scope)
            isNameStringLead(opcode) -> nameOrMethod(reader, start, depth, scope)
            else -> null
        }
        return result
    }

    private fun constant(reader: AmlByteReader, value: ULong): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        return AmlTermArg.Integer(value, start, reader.position)
    }

    private fun prefixedInteger(reader: AmlByteReader, byteCount: Int): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val value = when (byteCount) {
            1 -> reader.readU8()?.toULong()
            2 -> reader.readU16()?.toULong()
            4 -> reader.readU32()?.toULong()
            8 -> reader.readU64()
            else -> null
        } ?: return null
        return AmlTermArg.Integer(value, start, reader.position)
    }

    private fun string(reader: AmlByteReader): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        reader.readNullTerminatedAscii() ?: return null
        return AmlTermArg.Deferred(AML_STRING_PREFIX, start, reader.position)
    }

    private fun external(reader: AmlByteReader, start: Int): AmlTermArg? {
        reader.readU8() ?: return null
        reader.readNamePath() ?: return null
        reader.readU8() ?: return null
        reader.readU8() ?: return null
        return AmlTermArg.Deferred(AML_EXTERNAL_OP, start, reader.position)
    }

    private fun packageTerm(reader: AmlByteReader, opcode: UInt): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val length = reader.readPackageLength() ?: return null
        if (!reader.seek(length.end)) return null
        return AmlTermArg.Deferred(opcode, start, reader.position)
    }

    private fun controlPackage(
        reader: AmlByteReader,
        opcode: UInt,
        scope: AmlName,
        depth: Int,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val length = reader.readPackageLength() ?: return null
        val body = reader.slice(length.contentStart, length.end) ?: return null
        if (read(body, scope, depth + 1) == null) return null
        if (!reader.seek(length.end)) return null
        return AmlTermArg.Deferred(opcode, start, reader.position)
    }

    private fun plainPackage(reader: AmlByteReader, opcode: UInt): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val length = reader.readPackageLength() ?: return null
        if (!reader.seek(length.end)) return null
        return AmlTermArg.Deferred(opcode, start, reader.position)
    }

    private fun binaryTarget(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
        operandCount: Int = 2,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val operands = buildList {
            repeat(operandCount) {
                add(read(reader, scope, depth + 1) ?: return null)
            }
        }
        consumeTarget(reader) ?: return null
        return AmlTermArg.Operation(opcode, operands, start, reader.position)
    }

    private fun divide(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val dividend = read(reader, scope, depth + 1) ?: return null
        val divisor = read(reader, scope, depth + 1) ?: return null
        consumeTarget(reader) ?: return null
        consumeTarget(reader) ?: return null
        return AmlTermArg.Operation(opcode, listOf(dividend, divisor), start, reader.position)
    }

    private fun targetOperation(reader: AmlByteReader, opcode: UInt): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        consumeTarget(reader) ?: return null
        return AmlTermArg.Operation(opcode, emptyList(), start, reader.position)
    }

    private fun unaryOperation(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val operand = read(reader, scope, depth + 1) ?: return null
        return AmlTermArg.Operation(opcode, listOf(operand), start, reader.position)
    }

    private fun unaryTarget(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val operand = read(reader, scope, depth + 1) ?: return null
        consumeTarget(reader) ?: return null
        return AmlTermArg.Operation(opcode, listOf(operand), start, reader.position)
    }

    private fun binaryOperation(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        val left = read(reader, scope, depth + 1) ?: return null
        val right = read(reader, scope, depth + 1) ?: return null
        return AmlTermArg.Operation(opcode, listOf(left, right), start, reader.position)
    }

    private fun notify(
        reader: AmlByteReader,
        start: Int,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        reader.readU8() ?: return null
        reader.readNamePath() ?: return null
        read(reader, scope, depth + 1) ?: return null
        return AmlTermArg.Deferred(AML_NOTIFY_OP, start, reader.position)
    }

    private fun index(
        reader: AmlByteReader,
        start: Int,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        reader.readU8() ?: return null
        read(reader, scope, depth + 1) ?: return null
        read(reader, scope, depth + 1) ?: return null
        consumeTarget(reader) ?: return null
        return AmlTermArg.Deferred(AML_INDEX_OP, start, reader.position)
    }

    private fun createField(
        reader: AmlByteReader,
        opcode: UInt,
        depth: Int,
        scope: AmlName,
        operandCount: Int,
    ): AmlTermArg? {
        val start = reader.position
        reader.readU8() ?: return null
        repeat(operandCount) {
            read(reader, scope, depth + 1) ?: return null
        }
        consumeTarget(reader) ?: return null
        return AmlTermArg.Deferred(opcode, start, reader.position)
    }

    private fun extended(
        reader: AmlByteReader,
        start: Int,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        reader.readU8() ?: return null
        val ext = reader.readU8() ?: return null
        when (ext) {
            AML_EXT_COND_REF_OF_OP -> {
                reader.readNamePath() ?: return null
                consumeTarget(reader) ?: return null
            }
            AML_EXT_STALL_OP, AML_EXT_SLEEP_OP -> read(reader, scope, depth + 1) ?: return null
            AML_EXT_ACQUIRE_OP -> {
                reader.readNamePath() ?: return null
                reader.readU16() ?: return null
            }
            AML_EXT_RELEASE_OP -> reader.readNamePath() ?: return null
            AML_EXT_REVISION_OP, AML_EXT_DEBUG_OP, AML_EXT_TIMER_OP -> Unit
            else -> return null
        }
        return AmlTermArg.Deferred(ext, start, reader.position)
    }

    private fun nameOrMethod(
        reader: AmlByteReader,
        start: Int,
        depth: Int,
        scope: AmlName,
    ): AmlTermArg? {
        val path = reader.readNamePath() ?: return null
        val node = namespace.resolve(scope, path)
        val argumentCount = when (val value = node?.value) {
            is AmlMethod -> value.argumentCount
            is AmlExternalMethod -> value.argumentCount
            else -> 0
        }
        val arguments = buildList {
            repeat(argumentCount) {
                add(read(reader, scope, depth + 1) ?: return null)
            }
        }
        return if (arguments.isEmpty() && argumentCount == 0) {
            AmlTermArg.Name(path, scope, start, reader.position)
        } else {
            AmlTermArg.Operation(0u, arguments, start, reader.position)
        }
    }

    private fun consumeTarget(reader: AmlByteReader): AmlTermArg? {
        val start = reader.position
        val opcode = reader.peek() ?: return null
        if (opcode == AML_EXT_OP_PREFIX) {
            reader.readU8()
            return if (reader.readU8() == AML_EXT_DEBUG_OP) {
                AmlTermArg.Deferred(opcode, start, reader.position)
            } else {
                null
            }
        }
        if (opcode == 0x00u || opcode in AML_LOCAL0_OP..AML_LOCAL7_OP ||
            opcode in AML_ARG0_OP..AML_ARG6_OP
        ) {
            reader.readU8()
            return AmlTermArg.Deferred(opcode, start, reader.position)
        }
        if (!isNameStringLead(opcode)) return null
        reader.readNamePath() ?: return null
        return AmlTermArg.Deferred(opcode, start, reader.position)
    }
}

private const val MAX_AML_TERM_DEPTH = 256
