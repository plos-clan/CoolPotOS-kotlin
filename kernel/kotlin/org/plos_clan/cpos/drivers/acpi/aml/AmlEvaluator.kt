@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import bridge.asm_pause
import org.plos_clan.cpos.drivers.TscClock

private const val AML_LOCAL0_OP = 0x60u
private const val AML_LOCAL7_OP = 0x67u
private const val AML_ARG0_OP = 0x68u
private const val AML_ARG6_OP = 0x6Eu

private const val AML_STORE_OP = 0x70u
private const val AML_REF_OF_OP = 0x71u
private const val AML_ADD_OP = 0x72u
private const val AML_CONCAT_OP = 0x73u
private const val AML_SUBTRACT_OP = 0x74u
private const val AML_INCREMENT_OP = 0x75u
private const val AML_DECREMENT_OP = 0x76u
private const val AML_MULTIPLY_OP = 0x77u
private const val AML_DIVIDE_OP = 0x78u
private const val AML_SHIFT_LEFT_OP = 0x79u
private const val AML_SHIFT_RIGHT_OP = 0x7Au
private const val AML_AND_OP = 0x7Bu
private const val AML_NAND_OP = 0x7Cu
private const val AML_OR_OP = 0x7Du
private const val AML_NOR_OP = 0x7Eu
private const val AML_XOR_OP = 0x7Fu
private const val AML_NOT_OP = 0x80u
private const val AML_DEREF_OF_OP = 0x83u
private const val AML_CONCAT_RES_OP = 0x84u
private const val AML_MOD_OP = 0x85u
private const val AML_NOTIFY_OP = 0x86u
private const val AML_SIZE_OF_OP = 0x87u
private const val AML_INDEX_OP = 0x88u
private const val AML_OBJECT_TYPE_OP = 0x8Eu
private const val AML_LAND_OP = 0x90u
private const val AML_LOR_OP = 0x91u
private const val AML_LNOT_OP = 0x92u
private const val AML_LEQUAL_OP = 0x93u
private const val AML_LGREATER_OP = 0x94u
private const val AML_LLESS_OP = 0x95u
private const val AML_TO_BUFFER_OP = 0x96u
private const val AML_TO_INTEGER_OP = 0x99u
private const val AML_COPY_OBJECT_OP = 0x9Du
private const val AML_CONTINUE_OP = 0x9Fu
private const val AML_IF_OP = 0xA0u
private const val AML_ELSE_OP = 0xA1u
private const val AML_WHILE_OP = 0xA2u
private const val AML_NOOP_OP = 0xA3u
private const val AML_RETURN_OP = 0xA4u
private const val AML_BREAK_OP = 0xA5u

private const val AML_BYTE_PREFIX = 0x0Au
private const val AML_WORD_PREFIX = 0x0Bu
private const val AML_DWORD_PREFIX = 0x0Cu
private const val AML_STRING_PREFIX = 0x0Du
private const val AML_QWORD_PREFIX = 0x0Eu
private const val AML_BUFFER_OP = 0x11u
private const val AML_PACKAGE_OP = 0x12u
private const val AML_VAR_PACKAGE_OP = 0x13u
private const val AML_EXT_OP_PREFIX = 0x5Bu
private const val AML_EXT_COND_REF_OF_OP = 0x12u
private const val AML_EXT_STALL_OP = 0x21u
private const val AML_EXT_SLEEP_OP = 0x22u
private const val AML_EXT_ACQUIRE_OP = 0x23u
private const val AML_EXT_RELEASE_OP = 0x27u
private const val AML_EXT_REVISION_OP = 0x30u
private const val AML_EXT_DEBUG_OP = 0x31u
private const val AML_EXT_TIMER_OP = 0x33u

private const val MAX_METHOD_DEPTH = 32
private const val MAX_METHOD_OPERATIONS = 100_000
private const val MAX_WHILE_ITERATIONS = 4_096
private const val MAX_RUNTIME_BUFFER_SIZE = 1_048_576
private const val MAX_RUNTIME_PACKAGE_ELEMENTS = 65_536
private const val MAX_AML_DELAY_MICROSECONDS = 10_000_000uL

private sealed class AmlFlow {
    data object Next : AmlFlow()
    data class Returned(val value: AmlObject) : AmlFlow()
    data object Break : AmlFlow()
    data object Continue : AmlFlow()
    data object Failed : AmlFlow()
}

private class AmlBudget(var remaining: Int = MAX_METHOD_OPERATIONS) {
    fun consume(): Boolean = --remaining >= 0
}

private class AmlExecutionContext(
    val scope: AmlName,
    arguments: List<AmlObject>,
    val depth: Int,
    val budget: AmlBudget,
) {
    val args = MutableList<AmlObject>(7) { arguments.getOrElse(it) { AmlUninitialized } }
    val locals = MutableList<AmlObject>(8) { AmlUninitialized }
}

internal class AmlEvaluator(
    private val namespace: AmlNamespace,
    private val regions: AmlRegionManager,
    private val notificationSink: (AmlName, ULong) -> Unit = { _, _ -> },
) {
    fun evaluate(name: AmlName, arguments: List<AmlObject> = emptyList()): AmlObject? {
        val node = namespace.find(name) ?: return null
        return evaluateNode(node, arguments, 0, AmlBudget())
    }

    fun evaluate(node: AmlNamespaceNode, arguments: List<AmlObject> = emptyList()): AmlObject? =
        evaluateNode(node, arguments, 0, AmlBudget())

    fun evaluate(value: AmlObject): AmlObject? = when (value) {
        is AmlAlias -> namespace.resolve(value.declarationScope, value.target)
            ?.let { evaluateNode(it, emptyList(), 0, AmlBudget()) }
        else -> value.dereference()
    }

    private fun evaluateNode(
        node: AmlNamespaceNode,
        arguments: List<AmlObject>,
        depth: Int,
        budget: AmlBudget,
        visitedAliases: MutableSet<AmlName> = mutableSetOf(),
    ): AmlObject? {
        if (depth > MAX_METHOD_DEPTH || !visitedAliases.add(node.name)) {
            return null
        }
        return when (val value = node.value) {
            is AmlAlias -> {
                val target = namespace.resolve(value.declarationScope, value.target) ?: return null
                evaluateNode(target, arguments, depth, budget, visitedAliases)
            }
            is AmlMethod -> invoke(value, arguments, depth + 1, budget)
            is AmlFieldUnit -> regions.read(value)
            else -> value.dereference()
        }
    }

    private fun invoke(
        method: AmlMethod,
        arguments: List<AmlObject>,
        depth: Int,
        budget: AmlBudget,
    ): AmlObject? {
        if (depth > MAX_METHOD_DEPTH || arguments.size < method.argumentCount) {
            return null
        }
        val reader = AmlByteReader(
            source = method.source,
            start = method.bodyStart,
            end = method.bodyEnd,
        )
        val context = AmlExecutionContext(
            scope = method.declarationScope,
            arguments = arguments,
            depth = depth,
            budget = budget,
        )
        return when (val flow = executeTermList(reader, context)) {
            is AmlFlow.Returned -> flow.value.dereference()
            AmlFlow.Next -> AmlInteger(0uL)
            else -> null
        }
    }

    private fun executeTermList(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlFlow {
        while (!reader.exhausted) {
            if (!context.budget.consume()) {
                return AmlFlow.Failed
            }
            when (val flow = executeTerm(reader, context)) {
                AmlFlow.Next -> Unit
                else -> return flow
            }
        }
        return AmlFlow.Next
    }

    private fun executeTerm(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlFlow = when (reader.peek()) {
        AML_RETURN_OP -> {
            reader.readU8()
            AmlFlow.Returned(readTermArg(reader, context)?.dereference() ?: AmlUninitialized)
        }
        AML_STORE_OP -> {
            readTermArg(reader, context)
            AmlFlow.Next
        }
        AML_IF_OP -> executeIf(reader, context)
        AML_WHILE_OP -> executeWhile(reader, context)
        AML_BREAK_OP -> {
            reader.readU8()
            AmlFlow.Break
        }
        AML_CONTINUE_OP -> {
            reader.readU8()
            AmlFlow.Continue
        }
        AML_NOOP_OP -> {
            reader.readU8()
            AmlFlow.Next
        }
        AML_ELSE_OP -> {
            reader.readU8()
            skipPackage(reader)
            AmlFlow.Next
        }
        else -> if (readTermArg(reader, context) != null) AmlFlow.Next else AmlFlow.Failed
    }

    private fun executeIf(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlFlow {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return AmlFlow.Failed
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return AmlFlow.Failed
        val predicate = readTermArg(body, context)?.integerValue() ?: return AmlFlow.Failed
        val trueFlow = if (predicate != 0uL) executeTermList(body, context) else AmlFlow.Next
        reader.seek(packageLength.end)

        if (reader.peek() == AML_ELSE_OP) {
            reader.readU8()
            val elseLength = reader.readPackageLength() ?: return AmlFlow.Failed
            if (predicate == 0uL) {
                val elseBody = reader.slice(elseLength.contentStart, elseLength.end)
                    ?: return AmlFlow.Failed
                val elseFlow = executeTermList(elseBody, context)
                reader.seek(elseLength.end)
                return elseFlow
            }
            reader.seek(elseLength.end)
        }
        return trueFlow
    }

    private fun executeWhile(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlFlow {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return AmlFlow.Failed
        var iterations = 0
        while (iterations++ < MAX_WHILE_ITERATIONS) {
            val body = reader.slice(packageLength.contentStart, packageLength.end)
                ?: return AmlFlow.Failed
            val predicate = readTermArg(body, context)?.integerValue() ?: return AmlFlow.Failed
            if (predicate == 0uL) {
                break
            }
            when (val flow = executeTermList(body, context)) {
                AmlFlow.Next, AmlFlow.Continue -> Unit
                AmlFlow.Break -> break
                else -> {
                    reader.seek(packageLength.end)
                    return flow
                }
            }
        }
        reader.seek(packageLength.end)
        return if (iterations > MAX_WHILE_ITERATIONS) AmlFlow.Failed else AmlFlow.Next
    }

    private fun readTermArg(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlObject? {
        val opcode = reader.peek() ?: return null
        return when {
            opcode == 0x00u -> reader.readU8()?.let { AmlInteger(0uL) }
            opcode == 0x01u -> reader.readU8()?.let { AmlInteger(1uL) }
            opcode == 0xFFu -> reader.readU8()?.let { AmlInteger(ULong.MAX_VALUE) }
            opcode == AML_BYTE_PREFIX -> prefixedInteger(reader, 1)
            opcode == AML_WORD_PREFIX -> prefixedInteger(reader, 2)
            opcode == AML_DWORD_PREFIX -> prefixedInteger(reader, 4)
            opcode == AML_QWORD_PREFIX -> prefixedInteger(reader, 8)
            opcode == AML_STRING_PREFIX -> {
                reader.readU8()
                reader.readNullTerminatedAscii()?.let(::AmlString)
            }
            opcode == AML_BUFFER_OP -> readBuffer(reader, context)
            opcode == AML_PACKAGE_OP || opcode == AML_VAR_PACKAGE_OP -> readPackage(reader, context)
            opcode in AML_LOCAL0_OP..AML_LOCAL7_OP -> {
                reader.readU8()
                context.locals[(opcode - AML_LOCAL0_OP).toInt()].dereference()
            }
            opcode in AML_ARG0_OP..AML_ARG6_OP -> {
                reader.readU8()
                context.args[(opcode - AML_ARG0_OP).toInt()].dereference()
            }
            opcode == AML_STORE_OP -> store(reader, context)
            opcode == AML_REF_OF_OP -> {
                reader.readU8()
                readTarget(reader, context)
            }
            opcode == AML_ADD_OP || opcode == AML_SUBTRACT_OP -> binaryWithTarget(reader, context, opcode)
            opcode == AML_INCREMENT_OP || opcode == AML_DECREMENT_OP -> increment(reader, context, opcode)
            opcode in AML_MULTIPLY_OP..AML_XOR_OP -> arithmetic(reader, context, opcode)
            opcode == AML_NOT_OP -> unaryWithTarget(reader, context) { it.inv() }
            opcode == AML_DEREF_OF_OP -> {
                reader.readU8()
                readTermArg(reader, context)?.dereference()
            }
            opcode == AML_CONCAT_RES_OP || opcode == AML_CONCAT_OP -> concat(reader, context)
            opcode == AML_MOD_OP -> binaryWithTarget(reader, context, opcode)
            opcode == AML_NOTIFY_OP -> notify(reader, context)
            opcode == AML_SIZE_OF_OP -> sizeOf(reader, context)
            opcode == AML_INDEX_OP -> index(reader, context)
            opcode == AML_OBJECT_TYPE_OP -> objectType(reader, context)
            opcode in AML_LAND_OP..AML_LLESS_OP -> logical(reader, context, opcode)
            opcode == AML_TO_BUFFER_OP -> toBuffer(reader, context)
            opcode == AML_TO_INTEGER_OP -> toInteger(reader, context)
            opcode == AML_COPY_OBJECT_OP -> copyObject(reader, context)
            opcode == AML_EXT_OP_PREFIX -> extendedTerm(reader, context)
            isNameStringLead(opcode) -> resolveName(reader, context)
            else -> null
        }
    }

    private fun prefixedInteger(reader: AmlByteReader, byteCount: Int): AmlObject? {
        reader.readU8()
        val value = when (byteCount) {
            1 -> reader.readU8()?.toULong()
            2 -> reader.readU16()?.toULong()
            4 -> reader.readU32()?.toULong()
            8 -> reader.readU64()
            else -> null
        }
        return value?.let(::AmlInteger)
    }

    private fun resolveName(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        val path = reader.readNamePath() ?: return null
        val node = namespace.resolve(context.scope, path) ?: return AmlUninitialized
        val value = node.value
        return if (value is AmlMethod) {
            val args = buildList {
                repeat(value.argumentCount) {
                    add(readTermArg(reader, context) ?: return null)
                }
            }
            invoke(value, args, context.depth + 1, context.budget)
        } else {
            evaluateNode(node, emptyList(), context.depth, context.budget)
        }
    }

    private fun store(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader, context)?.dereference() ?: return null
        val target = readTarget(reader, context) ?: return null
        return if (target.write(source)) source else null
    }

    private fun readTarget(
        reader: AmlByteReader,
        context: AmlExecutionContext,
    ): AmlReference? {
        val opcode = reader.peek() ?: return null
        if (opcode == 0x00u) {
            reader.readU8()
            return AmlReference({ AmlUninitialized }, { true })
        }
        if (opcode in AML_LOCAL0_OP..AML_LOCAL7_OP) {
            reader.readU8()
            val index = (opcode - AML_LOCAL0_OP).toInt()
            return AmlReference({ context.locals[index] }) {
                context.locals[index] = it
                true
            }
        }
        if (opcode in AML_ARG0_OP..AML_ARG6_OP) {
            reader.readU8()
            val index = (opcode - AML_ARG0_OP).toInt()
            return AmlReference({ context.args[index] }) {
                context.args[index] = it
                true
            }
        }
        if (opcode == AML_INDEX_OP) {
            return index(reader, context) as? AmlReference
        }
        if (!isNameStringLead(opcode)) {
            return null
        }

        val path = reader.readNamePath() ?: return null
        val node = namespace.resolve(context.scope, path) ?: return null
        return AmlReference(
            read = { evaluateNode(node, emptyList(), context.depth, context.budget) ?: AmlUninitialized },
            write = { value ->
                when (val target = node.value) {
                    is AmlFieldUnit -> regions.write(target, value)
                    is AmlAlias -> {
                        val aliasNode = namespace.resolve(target.declarationScope, target.target)
                            ?: return@AmlReference false
                        when (val aliasValue = aliasNode.value) {
                            is AmlFieldUnit -> regions.write(aliasValue, value)
                            else -> {
                                aliasNode.value = value
                                true
                            }
                        }
                    }
                    else -> {
                        node.value = value
                        true
                    }
                }
            },
        )
    }

    private fun binaryWithTarget(
        reader: AmlByteReader,
        context: AmlExecutionContext,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader, context)?.integerValue() ?: return null
        val right = readTermArg(reader, context)?.integerValue() ?: return null
        val value = when (opcode) {
            AML_ADD_OP -> left + right
            AML_SUBTRACT_OP -> left - right
            AML_MOD_OP -> if (right == 0uL) return null else left % right
            else -> return null
        }
        val result = AmlInteger(value)
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun arithmetic(
        reader: AmlByteReader,
        context: AmlExecutionContext,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader, context)?.integerValue() ?: return null
        val right = readTermArg(reader, context)?.integerValue() ?: return null
        if (opcode == AML_DIVIDE_OP) {
            if (right == 0uL) return null
            val remainder = AmlInteger(left % right)
            val quotient = AmlInteger(left / right)
            val remainderTarget = readTarget(reader, context) ?: return null
            val quotientTarget = readTarget(reader, context) ?: return null
            return quotient.takeIf { remainderTarget.write(remainder) && quotientTarget.write(quotient) }
        }
        val value = when (opcode) {
            AML_MULTIPLY_OP -> left * right
            AML_SHIFT_LEFT_OP -> left shl (right and 63uL).toInt()
            AML_SHIFT_RIGHT_OP -> left shr (right and 63uL).toInt()
            AML_AND_OP -> left and right
            AML_NAND_OP -> (left and right).inv()
            AML_OR_OP -> left or right
            AML_NOR_OP -> (left or right).inv()
            AML_XOR_OP -> left xor right
            else -> return null
        }
        val result = AmlInteger(value)
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun increment(
        reader: AmlByteReader,
        context: AmlExecutionContext,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val target = readTarget(reader, context) ?: return null
        val current = target.read().integerValue() ?: return null
        val result = AmlInteger(if (opcode == AML_INCREMENT_OP) current + 1uL else current - 1uL)
        return result.takeIf { target.write(it) }
    }

    private inline fun unaryWithTarget(
        reader: AmlByteReader,
        context: AmlExecutionContext,
        operation: (ULong) -> ULong,
    ): AmlObject? {
        reader.readU8()
        val operand = readTermArg(reader, context)?.integerValue() ?: return null
        val result = AmlInteger(operation(operand))
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun concat(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader, context)?.dereference() ?: return null
        val right = readTermArg(reader, context)?.dereference() ?: return null
        val result = when {
            left is AmlString && right is AmlString -> AmlString(left.value + right.value)
            else -> {
                val leftBytes = left.toBytes()
                val rightBytes = right.toBytes()
                if (leftBytes.size > MAX_RUNTIME_BUFFER_SIZE - rightBytes.size) return null
                AmlBuffer(leftBytes + rightBytes)
            }
        }
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun notify(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val path = reader.readNamePath() ?: return null
        val node = namespace.resolve(context.scope, path) ?: return null
        val value = readTermArg(reader, context)?.integerValue() ?: return null
        notificationSink(node.name, value)
        return AmlInteger(value)
    }

    private fun sizeOf(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        return when (val value = readTermArg(reader, context)?.dereference()) {
            is AmlBuffer -> AmlInteger(value.bytes.size.toULong())
            is AmlString -> AmlInteger(value.value.encodeToByteArray().size.toULong() + 1uL)
            is AmlPackage -> AmlInteger(value.elements.size.toULong())
            else -> null
        }
    }

    private fun index(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader, context)?.dereference() ?: return null
        val indexValue = readTermArg(reader, context)?.integerValue() ?: return null
        if (indexValue > Int.MAX_VALUE.toULong()) return null
        val index = indexValue.toInt()
        val reference = when (source) {
            is AmlBuffer -> {
                if (index !in source.bytes.indices) return null
                AmlReference(
                    read = { AmlInteger(source.bytes[index].toUByte().toULong()) },
                    write = {
                        val value = it.integerValue() ?: return@AmlReference false
                        source.bytes[index] = value.toByte()
                        true
                    },
                )
            }
            is AmlPackage -> {
                if (index !in source.elements.indices) return null
                AmlReference(
                    read = { source.elements[index] },
                    write = {
                        source.elements[index] = it
                        true
                    },
                )
            }
            else -> return null
        }
        val target = readTarget(reader, context) ?: return null
        return reference.takeIf { target.write(it) }
    }

    private fun objectType(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val value = readTermArg(reader, context)?.dereference() ?: return null
        val type = when (value) {
            is AmlInteger -> 1uL
            is AmlString -> 2uL
            is AmlBuffer -> 3uL
            is AmlPackage -> 4uL
            is AmlFieldUnit -> 5uL
            is AmlDevice -> 6uL
            is AmlEvent -> 7uL
            is AmlMethod -> 8uL
            is AmlMutex -> 9uL
            is AmlOperationRegion -> 10uL
            is AmlProcessor -> 12uL
            is AmlThermalZone -> 13uL
            else -> 0uL
        }
        return AmlInteger(type)
    }

    private fun logical(
        reader: AmlByteReader,
        context: AmlExecutionContext,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader, context)?.integerValue() ?: return null
        val result = when (opcode) {
            AML_LNOT_OP -> left == 0uL
            else -> {
                val right = readTermArg(reader, context)?.integerValue() ?: return null
                when (opcode) {
                    AML_LAND_OP -> left != 0uL && right != 0uL
                    AML_LOR_OP -> left != 0uL || right != 0uL
                    AML_LEQUAL_OP -> left == right
                    AML_LGREATER_OP -> left > right
                    AML_LLESS_OP -> left < right
                    else -> return null
                }
            }
        }
        return AmlInteger(if (result) ULong.MAX_VALUE else 0uL)
    }

    private fun toBuffer(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader, context)?.dereference() ?: return null
        val result = AmlBuffer(source.toBytes())
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun toInteger(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val result = AmlInteger(readTermArg(reader, context)?.integerValue() ?: return null)
        val target = readTarget(reader, context) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun copyObject(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val value = readTermArg(reader, context)?.dereference() ?: return null
        val copy = when (value) {
            is AmlBuffer -> AmlBuffer(value.bytes.copyOf())
            is AmlPackage -> AmlPackage(value.elements.toMutableList())
            else -> value
        }
        val target = readTarget(reader, context) ?: return null
        return copy.takeIf { target.write(it) }
    }

    private fun readBuffer(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val sizeValue = readTermArg(body, context)?.integerValue() ?: return null
        if (sizeValue > MAX_RUNTIME_BUFFER_SIZE.toULong()) return null
        val size = sizeValue.toInt()
        val data = body.readBytes(minOf(size, body.remaining)) ?: return null
        reader.seek(packageLength.end)
        return AmlBuffer(if (data.size < size) data.copyOf(size) else data)
    }

    private fun readPackage(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        val variable = reader.readU8() == AML_VAR_PACKAGE_OP
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val countValue = if (variable) {
            readTermArg(body, context)?.integerValue()
        } else {
            body.readU8()?.toULong()
        }
        if (countValue == null || countValue > MAX_RUNTIME_PACKAGE_ELEMENTS.toULong()) return null
        val count = countValue.toInt()
        val elements = MutableList<AmlObject>(count) { AmlUninitialized }
        for (index in 0 until count) {
            if (!body.exhausted) {
                elements[index] = readTermArg(body, context) ?: AmlUninitialized
            }
        }
        reader.seek(packageLength.end)
        return AmlPackage(elements)
    }

    private fun extendedTerm(reader: AmlByteReader, context: AmlExecutionContext): AmlObject? {
        reader.readU8()
        val opcode = reader.readU8() ?: return null
        return when (opcode) {
            AML_EXT_COND_REF_OF_OP -> {
                val targetObject = readTarget(reader, context)
                val resultTarget = readTarget(reader, context) ?: return null
                AmlInteger(if (targetObject != null && resultTarget.write(targetObject)) ULong.MAX_VALUE else 0uL)
            }
            AML_EXT_STALL_OP, AML_EXT_SLEEP_OP -> {
                val requested = readTermArg(reader, context)?.integerValue() ?: return null
                val microseconds = if (opcode == AML_EXT_SLEEP_OP) {
                    if (requested > MAX_AML_DELAY_MICROSECONDS / 1_000uL) return null
                    requested * 1_000uL
                } else {
                    requested
                }
                if (!delayMicroseconds(microseconds)) return null
                AmlInteger(0uL)
            }
            AML_EXT_ACQUIRE_OP -> {
                readTarget(reader, context) ?: return null
                reader.readU16() ?: return null
                AmlInteger(0uL)
            }
            AML_EXT_RELEASE_OP -> {
                readTarget(reader, context) ?: return null
                AmlInteger(0uL)
            }
            AML_EXT_REVISION_OP -> AmlInteger(2uL)
            AML_EXT_DEBUG_OP -> AmlReference({ AmlUninitialized }, { true })
            AML_EXT_TIMER_OP -> AmlInteger(TscClock.nanoTime() / 100uL)
            else -> null
        }
    }

    private fun skipPackage(reader: AmlByteReader): Boolean {
        val length = reader.readPackageLength() ?: return false
        return reader.seek(length.end)
    }

    private fun delayMicroseconds(microseconds: ULong): Boolean {
        if (microseconds > MAX_AML_DELAY_MICROSECONDS) {
            return false
        }
        val duration = microseconds * 1_000uL
        if (duration == 0uL) {
            return true
        }
        if (!TscClock.isReady) {
            return false
        }
        val start = TscClock.nanoTime()
        while (TscClock.nanoTime() - start < duration) {
            asm_pause()
        }
        return true
    }
}

private fun AmlObject.toBytes(): ByteArray = when (val value = dereference()) {
    is AmlBuffer -> value.bytes.copyOf()
    is AmlString -> value.value.encodeToByteArray() + byteArrayOf(0)
    is AmlInteger -> ByteArray(ULong.SIZE_BYTES) { index ->
        (value.value shr (index * 8)).toByte()
    }
    else -> ByteArray(0)
}
