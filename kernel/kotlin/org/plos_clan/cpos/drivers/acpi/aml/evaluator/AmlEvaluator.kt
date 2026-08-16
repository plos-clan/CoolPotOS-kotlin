package org.plos_clan.cpos.drivers.acpi.aml

internal class AmlEvaluator(
    internal val namespace: AmlNamespace,
    internal val regions: AmlRegionManager,
    internal val notificationSink: (AmlName, ULong) -> Unit = { _, _ -> },
) {
    fun evaluate(name: AmlName, arguments: List<AmlObject> = emptyList()): AmlObject? =
        namespace.find(name)?.let { evaluateNode(it, arguments, 0, AmlBudget()) }

    fun evaluate(node: AmlNamespaceNode, arguments: List<AmlObject> = emptyList()): AmlObject? =
        evaluateNode(node, arguments, 0, AmlBudget())

    fun evaluate(value: AmlObject): AmlObject? = when (value) {
        is AmlAlias -> namespace.resolve(value.declarationScope, value.target)
            ?.let { evaluateNode(it, emptyList(), 0, AmlBudget()) }
        else -> value.dereference()
    }

    internal fun evaluateNode(
        node: AmlNamespaceNode,
        arguments: List<AmlObject>,
        depth: Int,
        budget: AmlBudget,
        visitedAliases: MutableSet<AmlName> = mutableSetOf(),
    ): AmlObject? {
        if (depth > MAX_METHOD_DEPTH || !visitedAliases.add(node.name)) return null
        return when (val value = node.value) {
            is AmlAlias -> namespace.resolve(value.declarationScope, value.target)
                ?.let { evaluateNode(it, arguments, depth, budget, visitedAliases) }
            is AmlMethod -> invoke(value, arguments, depth + 1, budget)
            is AmlFieldUnit -> regions.read(value)
            else -> value.dereference()
        }
    }

    internal fun invoke(
        method: AmlMethod,
        arguments: List<AmlObject>,
        depth: Int,
        budget: AmlBudget,
    ): AmlObject? =
        if (depth > MAX_METHOD_DEPTH || arguments.size < method.argumentCount) null
        else AmlMethodFrame(this, method, arguments, depth, budget).execute()
}
