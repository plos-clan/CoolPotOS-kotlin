package org.plos_clan.cpos.drivers.acpi.aml

internal class AmlMethodFrame(
    private val evaluator: AmlEvaluator,
    method: AmlMethod,
    arguments: List<AmlObject>,
    private val depth: Int,
    private val budget: AmlBudget,
) {
    private val scope = method.declarationScope
    private val args = MutableList<AmlObject>(7) { arguments.getOrElse(it) { AmlUninitialized } }
    private val locals = MutableList<AmlObject>(8) { AmlUninitialized }
    private val reader = AmlByteReader(method.source, method.bodyStart, method.bodyEnd)

    fun execute(): AmlObject? =
        when (val flow = executeTermList(reader)) {
            is AmlFlow.Returned -> flow.value.dereference()
            AmlFlow.Next -> AmlInteger(0uL)
            else -> null
        }

    private fun executeTermList(reader: AmlByteReader): AmlFlow {
        while (!reader.exhausted) {
            if (!budget.consume()) {
                return AmlFlow.Failed
            }
            when (val flow = executeTerm(reader)) {
                AmlFlow.Next -> Unit
                else -> return flow
            }
        }
        return AmlFlow.Next
    }

    private fun executeTerm(reader: AmlByteReader): AmlFlow = when (reader.peek()) {
        AML_RETURN_OP -> {
            reader.readU8()
            AmlFlow.Returned(readTermArg(reader)?.dereference() ?: AmlUninitialized)
        }
        AML_STORE_OP -> {
            readTermArg(reader)
            AmlFlow.Next
        }
        AML_IF_OP -> executeIf(reader)
        AML_WHILE_OP -> executeWhile(reader)
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
        else -> if (readTermArg(reader) != null) AmlFlow.Next else AmlFlow.Failed
    }

    private fun executeIf(reader: AmlByteReader): AmlFlow {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return AmlFlow.Failed
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return AmlFlow.Failed
        val predicate = readTermArg(body)?.integerValue() ?: return AmlFlow.Failed
        val trueFlow = if (predicate != 0uL) executeTermList(body) else AmlFlow.Next
        reader.seek(packageLength.end)

        if (reader.peek() == AML_ELSE_OP) {
            reader.readU8()
            val elseLength = reader.readPackageLength() ?: return AmlFlow.Failed
            if (predicate == 0uL) {
                val elseBody = reader.slice(elseLength.contentStart, elseLength.end)
                    ?: return AmlFlow.Failed
                val elseFlow = executeTermList(elseBody)
                reader.seek(elseLength.end)
                return elseFlow
            }
            reader.seek(elseLength.end)
        }
        return trueFlow
    }

    private fun executeWhile(reader: AmlByteReader): AmlFlow {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return AmlFlow.Failed
        var iterations = 0
        while (iterations++ < MAX_WHILE_ITERATIONS) {
            val body = reader.slice(packageLength.contentStart, packageLength.end)
                ?: return AmlFlow.Failed
            val predicate = readTermArg(body)?.integerValue() ?: return AmlFlow.Failed
            if (predicate == 0uL) {
                break
            }
            when (val flow = executeTermList(body)) {
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

    internal fun readTermArg(reader: AmlByteReader): AmlObject? {
        val opcode = reader.peek() ?: return null
        return when {
            opcode == 0x00u -> reader.readU8()?.let { AmlInteger(0uL) }
            opcode == 0x01u -> reader.readU8()?.let { AmlInteger(1uL) }
            opcode == 0xFFu -> reader.readU8()?.let { AmlInteger(ULong.MAX_VALUE) }
            opcode == AmlRuntimeOpcode.AML_BYTE_PREFIX -> prefixedInteger(reader, 1)
            opcode == AmlRuntimeOpcode.AML_WORD_PREFIX -> prefixedInteger(reader, 2)
            opcode == AmlRuntimeOpcode.AML_DWORD_PREFIX -> prefixedInteger(reader, 4)
            opcode == AmlRuntimeOpcode.AML_QWORD_PREFIX -> prefixedInteger(reader, 8)
            opcode == AmlRuntimeOpcode.AML_STRING_PREFIX -> {
                reader.readU8()
                reader.readNullTerminatedAscii()?.let(::AmlString)
            }
            opcode == AmlRuntimeOpcode.AML_BUFFER_OP -> readBuffer(reader)
            opcode == AmlRuntimeOpcode.AML_PACKAGE_OP ||
                opcode == AmlRuntimeOpcode.AML_VAR_PACKAGE_OP ->
                readPackage(reader)
            opcode in AML_LOCAL0_OP..AML_LOCAL7_OP -> {
                reader.readU8()
                locals[(opcode - AML_LOCAL0_OP).toInt()].dereference()
            }
            opcode in AML_ARG0_OP..AML_ARG6_OP -> {
                reader.readU8()
                args[(opcode - AML_ARG0_OP).toInt()].dereference()
            }
            opcode == AML_STORE_OP -> store(reader)
            opcode == AML_REF_OF_OP -> {
                reader.readU8()
                readTarget(reader)
            }
            opcode == AML_ADD_OP || opcode == AML_SUBTRACT_OP -> binaryWithTarget(reader, opcode)
            opcode == AML_INCREMENT_OP || opcode == AML_DECREMENT_OP -> increment(reader, opcode)
            opcode in AML_MULTIPLY_OP..AML_XOR_OP -> arithmetic(reader, opcode)
            opcode == AML_NOT_OP -> unaryWithTarget(reader) { it.inv() }
            opcode == AML_DEREF_OF_OP -> {
                reader.readU8()
                readTermArg(reader)?.dereference()
            }
            opcode == AML_CONCAT_RES_OP || opcode == AML_CONCAT_OP -> concat(reader)
            opcode == AML_MOD_OP -> binaryWithTarget(reader, opcode)
            opcode == AML_NOTIFY_OP -> notify(reader)
            opcode == AML_SIZE_OF_OP -> sizeOf(reader)
            opcode == AML_INDEX_OP -> index(reader)
            opcode == AML_OBJECT_TYPE_OP -> objectType(reader)
            opcode in AML_LAND_OP..AML_LLESS_OP -> logical(reader, opcode)
            opcode == AML_TO_BUFFER_OP -> toBuffer(reader)
            opcode == AML_TO_INTEGER_OP -> toInteger(reader)
            opcode == AML_COPY_OBJECT_OP -> copyObject(reader)
            opcode == AmlRuntimeOpcode.AML_EXT_OP_PREFIX -> AmlExtendedTerm.evaluate(this, reader)
            isNameStringLead(opcode) -> resolveName(reader)
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

    private fun resolveName(reader: AmlByteReader): AmlObject? {
        val path = reader.readNamePath() ?: return null
        val node = evaluator.namespace.resolve(scope, path) ?: return AmlUninitialized
        val value = node.value
        return if (value is AmlMethod) {
            val args = buildList {
                repeat(value.argumentCount) {
                    add(readTermArg(reader) ?: return null)
                }
            }
            evaluator.invoke(value, args, depth + 1, budget)
        } else {
            evaluator.evaluateNode(node, emptyList(), depth, budget)
        }
    }

    private fun store(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader)?.dereference() ?: return null
        val target = readTarget(reader) ?: return null
        return if (target.write(source)) source else null
    }

    internal fun readTarget(reader: AmlByteReader): AmlReference? {
        val opcode = reader.peek() ?: return null
        if (opcode == 0x00u) {
            reader.readU8()
            return AmlReference({ AmlUninitialized }, { true })
        }
        if (opcode in AML_LOCAL0_OP..AML_LOCAL7_OP) {
            reader.readU8()
            val index = (opcode - AML_LOCAL0_OP).toInt()
            return AmlReference({ locals[index] }) {
                locals[index] = it
                true
            }
        }
        if (opcode in AML_ARG0_OP..AML_ARG6_OP) {
            reader.readU8()
            val index = (opcode - AML_ARG0_OP).toInt()
            return AmlReference({ args[index] }) {
                args[index] = it
                true
            }
        }
        if (opcode == AML_INDEX_OP) {
            return index(reader) as? AmlReference
        }
        if (!isNameStringLead(opcode)) {
            return null
        }

        val path = reader.readNamePath() ?: return null
        val node = evaluator.namespace.resolve(scope, path) ?: return null
        return AmlReference(
            read = {
                evaluator.evaluateNode(node, emptyList(), depth, budget) ?: AmlUninitialized
            },
            write = { value ->
                when (val target = node.value) {
                    is AmlFieldUnit -> evaluator.regions.write(target, value)
                    is AmlAlias -> {
                        val aliasNode = evaluator.namespace.resolve(target.declarationScope, target.target)
                            ?: return@AmlReference false
                        when (val aliasValue = aliasNode.value) {
                            is AmlFieldUnit -> evaluator.regions.write(aliasValue, value)
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
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader)?.integerValue() ?: return null
        val right = readTermArg(reader)?.integerValue() ?: return null
        val value = when (opcode) {
            AML_ADD_OP -> left + right
            AML_SUBTRACT_OP -> left - right
            AML_MOD_OP -> if (right == 0uL) return null else left % right
            else -> return null
        }
        val result = AmlInteger(value)
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun arithmetic(
        reader: AmlByteReader,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader)?.integerValue() ?: return null
        val right = readTermArg(reader)?.integerValue() ?: return null
        if (opcode == AML_DIVIDE_OP) {
            if (right == 0uL) return null
            val remainder = AmlInteger(left % right)
            val quotient = AmlInteger(left / right)
            val remainderTarget = readTarget(reader) ?: return null
            val quotientTarget = readTarget(reader) ?: return null
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
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun increment(
        reader: AmlByteReader,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val target = readTarget(reader) ?: return null
        val current = target.read().integerValue() ?: return null
        val result = AmlInteger(if (opcode == AML_INCREMENT_OP) current + 1uL else current - 1uL)
        return result.takeIf { target.write(it) }
    }

    private inline fun unaryWithTarget(
        reader: AmlByteReader,
        operation: (ULong) -> ULong,
    ): AmlObject? {
        reader.readU8()
        val operand = readTermArg(reader)?.integerValue() ?: return null
        val result = AmlInteger(operation(operand))
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun concat(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader)?.dereference() ?: return null
        val right = readTermArg(reader)?.dereference() ?: return null
        val result = when {
            left is AmlString && right is AmlString -> AmlString(left.value + right.value)
            else -> {
                val leftBytes = left.toBytes()
                val rightBytes = right.toBytes()
                if (leftBytes.size > MAX_RUNTIME_BUFFER_SIZE - rightBytes.size) return null
                AmlBuffer(leftBytes + rightBytes)
            }
        }
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun notify(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val path = reader.readNamePath() ?: return null
        val node = evaluator.namespace.resolve(scope, path) ?: return null
        val value = readTermArg(reader)?.integerValue() ?: return null
        evaluator.notificationSink(node.name, value)
        return AmlInteger(value)
    }

    private fun sizeOf(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        return when (val value = readTermArg(reader)?.dereference()) {
            is AmlBuffer -> AmlInteger(value.bytes.size.toULong())
            is AmlString -> AmlInteger(value.value.encodeToByteArray().size.toULong() + 1uL)
            is AmlPackage -> AmlInteger(value.elements.size.toULong())
            else -> null
        }
    }

    private fun index(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader)?.dereference() ?: return null
        val indexValue = readTermArg(reader)?.integerValue() ?: return null
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
        val target = readTarget(reader) ?: return null
        return reference.takeIf { target.write(it) }
    }

    private fun objectType(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        return AmlInteger(readTermArg(reader)?.dereference()?.objectType ?: return null)
    }

    private fun logical(
        reader: AmlByteReader,
        opcode: UInt,
    ): AmlObject? {
        reader.readU8()
        val left = readTermArg(reader)?.integerValue() ?: return null
        val result = when (opcode) {
            AML_LNOT_OP -> left == 0uL
            else -> {
                val right = readTermArg(reader)?.integerValue() ?: return null
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

    private fun toBuffer(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val source = readTermArg(reader)?.dereference() ?: return null
        val result = AmlBuffer(source.toBytes())
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun toInteger(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val result = AmlInteger(readTermArg(reader)?.integerValue() ?: return null)
        val target = readTarget(reader) ?: return null
        return result.takeIf { target.write(it) }
    }

    private fun copyObject(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val copy = readTermArg(reader)?.dereference()?.runtimeCopy() ?: return null
        val target = readTarget(reader) ?: return null
        return copy.takeIf { target.write(it) }
    }

    private fun readBuffer(reader: AmlByteReader): AmlObject? {
        reader.readU8()
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val sizeValue = readTermArg(body)?.integerValue() ?: return null
        if (sizeValue > MAX_RUNTIME_BUFFER_SIZE.toULong()) return null
        val size = sizeValue.toInt()
        val data = body.readBytes(minOf(size, body.remaining)) ?: return null
        reader.seek(packageLength.end)
        return AmlBuffer(if (data.size < size) data.copyOf(size) else data)
    }

    private fun readPackage(reader: AmlByteReader): AmlObject? {
        val variable = reader.readU8() == AmlRuntimeOpcode.AML_VAR_PACKAGE_OP
        val packageLength = reader.readPackageLength() ?: return null
        val body = reader.slice(packageLength.contentStart, packageLength.end) ?: return null
        val countValue = if (variable) {
            readTermArg(body)?.integerValue()
        } else {
            body.readU8()?.toULong()
        }
        if (countValue == null || countValue > MAX_RUNTIME_PACKAGE_ELEMENTS.toULong()) return null
        val count = countValue.toInt()
        val elements = MutableList<AmlObject>(count) { AmlUninitialized }
        for (index in 0 until count) {
            if (!body.exhausted) {
                elements[index] = readTermArg(body) ?: AmlUninitialized
            }
        }
        reader.seek(packageLength.end)
        return AmlPackage(elements)
    }

    private fun skipPackage(reader: AmlByteReader): Boolean {
        val length = reader.readPackageLength() ?: return false
        return reader.seek(length.end)
    }

}
