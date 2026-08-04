package org.plos_clan.cpos.drivers.acpi.aml

class AmlNamespaceNode internal constructor(
    val name: AmlName,
    val parent: AmlNamespaceNode?,
) {
    var value: AmlObject = AmlUninitialized
        internal set

    internal val children = mutableMapOf<String, AmlNamespaceNode>()

    fun child(segment: String): AmlNamespaceNode? = children[segment]

    val childNodes: List<AmlNamespaceNode>
        get() = children.values.toList()

    override fun toString(): String = name.toString()
}

class AmlNamespace {
    val root = AmlNamespaceNode(AmlName.ROOT, null)

    fun clear() {
        root.children.clear()
        root.value = AmlUninitialized
    }

    internal fun define(name: AmlName, value: AmlObject): AmlNamespaceNode {
        val node = ensure(name)
        node.value = value
        return node
    }

    internal fun ensure(name: AmlName): AmlNamespaceNode {
        var current = root
        name.segments.forEach { segment ->
            current = current.children.getOrPut(segment) {
                AmlNamespaceNode(current.name.child(segment), current)
            }
        }
        return current
    }

    fun find(name: AmlName): AmlNamespaceNode? {
        var current = root
        name.segments.forEach { segment ->
            current = current.children[segment] ?: return null
        }
        return current
    }

    internal fun resolve(
        scope: AmlName,
        path: AmlNamePath,
        searchParents: Boolean = true,
    ): AmlNamespaceNode? {
        if (path.absolute || path.parentPrefixCount != 0 || !searchParents) {
            return find(path.resolveFrom(scope))
        }
        if (path.segments.isEmpty()) {
            return find(scope)
        }

        var candidateScope = scope
        while (true) {
            find(AmlName(candidateScope.segments + path.segments))?.let { return it }
            if (candidateScope.isRoot) {
                return null
            }
            candidateScope = candidateScope.parent
        }
    }

    internal fun declarationName(scope: AmlName, path: AmlNamePath): AmlName =
        path.resolveFrom(scope)

    fun allNodes(): List<AmlNamespaceNode> = buildList {
        fun visit(node: AmlNamespaceNode) {
            add(node)
            node.children.values.forEach(::visit)
        }
        visit(root)
    }
}
