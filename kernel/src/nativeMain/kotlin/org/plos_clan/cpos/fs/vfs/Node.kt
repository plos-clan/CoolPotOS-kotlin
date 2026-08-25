package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.IrqSpinLock

class Inode internal constructor(
    val id: InodeId,
    val superBlock: SuperBlock,
    internal val backend: InodeBackend,
    initialAttributes: InodeAttributeSnapshot,
    val generation: ULong = 0uL,
) {
    private val lock = IrqSpinLock()
    private var currentMetadata = initialAttributes.attributes.metadata
    private var attributeSnapshot: InodeAttributeSnapshot? = initialAttributes
    private var attributeGeneration = 0uL
    private var extendedAttributes: MutableMap<ExtendedAttributeName, ByteArray>? = null
    private var openReferences = 0
    private var evicted = false

    val type: InodeType
        get() = backend.type

    fun metadata(): InodeMetadata = lock.withLock { currentMetadata }

    fun attributes(
        caller: VfsOperationContext,
        forceRefresh: Boolean = false,
    ): VfsResult<InodeAttributes> = when (
        val result = attributeSnapshot(caller, forceRefresh)
    ) {
        is VfsResult.Ok -> VfsResult.Ok(result.value.attributes)
        is VfsResult.Err -> result
    }

    internal fun attributeSnapshot(
        caller: VfsOperationContext,
        forceRefresh: Boolean = false,
    ): VfsResult<InodeAttributeSnapshot> {
        val request = lock.withLock {
            if (!forceRefresh) {
                attributeSnapshot?.takeIf {
                    it.validity.isValid(TscClock.nanoTime())
                }?.let { return VfsResult.Ok(it) }
            }
            ++attributeGeneration to currentMetadata
        }
        val loaded = when (val result = backend.loadAttributes(caller, this)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val snapshot = lock.withLock {
            if (evicted || currentMetadata != request.second) {
                InodeAttributeSnapshot(
                    loaded.attributes.copy(metadata = currentMetadata),
                    CacheValidity.Volatile,
                )
            } else {
                if (request.first == attributeGeneration) {
                    currentMetadata = loaded.attributes.metadata
                    attributeSnapshot = loaded
                }
                loaded
            }
        }
        return VfsResult.Ok(snapshot)
    }

    internal fun installAttributeSnapshot(snapshot: InodeAttributeSnapshot) = lock.withLock {
        if (evicted) return@withLock
        currentMetadata = snapshot.attributes.metadata
        attributeSnapshot = snapshot
        attributeGeneration++
    }

    internal fun invalidateAttributes() = lock.withLock {
        if (evicted) return@withLock
        attributeSnapshot = null
        attributeGeneration++
    }

    internal fun updateMetadata(
        timestamps: InodeTimestampUpdate = InodeTimestampEvent.STATUS_CHANGED,
        update: (InodeMetadata) -> InodeMetadata = { it },
    ) {
        var shouldEvict = false
        lock.withLock {
            if (!evicted) {
                currentMetadata = updateMetadataLocked(timestamps, update)
                attributeSnapshot = null
                attributeGeneration++
                if (currentMetadata.linkCount == 0u && openReferences == 0) {
                    evicted = true
                    shouldEvict = true
                }
            }
        }
        if (shouldEvict) {
            backend.evict(this)
        }
    }

    internal fun getExtendedAttribute(name: ExtendedAttributeName): VfsResult<ByteArray> =
        lock.withLock {
            val value = extendedAttributes?.get(name)
                ?: return@withLock VfsResult.Err(VfsError.NO_DATA)
            VfsResult.Ok(value.copyOf())
        }

    internal fun listExtendedAttributes(): VfsResult<ByteArray> = lock.withLock {
        val attributes = extendedAttributes ?: return@withLock VfsResult.Ok(ByteArray(0))
        val size = attributes.keys.sumOf { it.size + 1 }
        val result = ByteArray(size)
        var offset = 0
        for (name in attributes.keys) {
            name.copyInto(result, offset)
            offset += name.size + 1
        }
        VfsResult.Ok(result)
    }

    internal fun setExtendedAttribute(
        name: ExtendedAttributeName,
        value: ByteArray,
        mode: ExtendedAttributeMode,
    ): VfsResult<Unit> = lock.withLock {
        if (value.size > EXTENDED_ATTRIBUTE_VALUE_MAX) {
            return@withLock VfsResult.Err(VfsError.RANGE)
        }
        val attributes = extendedAttributes
        val exists = attributes?.containsKey(name) == true
        if (mode == ExtendedAttributeMode.CREATE && exists) {
            return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        }
        if (mode == ExtendedAttributeMode.REPLACE && !exists) {
            return@withLock VfsResult.Err(VfsError.NO_DATA)
        }
        if (!exists) {
            val listSize = attributes?.keys?.sumOf { it.size + 1 } ?: 0
            if (name.size + 1 > EXTENDED_ATTRIBUTE_VALUE_MAX - listSize) {
                return@withLock VfsResult.Err(VfsError.NO_SPACE)
            }
        }
        val destination = attributes ?: linkedMapOf<ExtendedAttributeName, ByteArray>().also {
            extendedAttributes = it
        }
        destination[name] = value.copyOf()
        currentMetadata = updateMetadataLocked(InodeTimestampEvent.STATUS_CHANGED)
        attributeSnapshot = null
        attributeGeneration++
        VfsResult.Ok(Unit)
    }

    internal fun removeExtendedAttribute(name: ExtendedAttributeName): VfsResult<Unit> =
        lock.withLock {
            val attributes = extendedAttributes
            if (attributes?.remove(name) == null) {
                return@withLock VfsResult.Err(VfsError.NO_DATA)
            }
            if (attributes.isEmpty()) extendedAttributes = null
            currentMetadata = updateMetadataLocked(InodeTimestampEvent.STATUS_CHANGED)
            attributeSnapshot = null
            attributeGeneration++
            VfsResult.Ok(Unit)
        }

    private fun updateMetadataLocked(
        timestamps: InodeTimestampUpdate,
        update: (InodeMetadata) -> InodeMetadata = { it },
    ): InodeMetadata {
        val metadata = update(currentMetadata)
        if (!timestamps.requiresCurrentTime) return metadata
        val updatedTimestamps = timestamps.apply(metadata.timestamps, VfsTimestamp.now())
        return if (updatedTimestamps == metadata.timestamps) metadata
        else metadata.copy(timestamps = updatedTimestamps)
    }

    internal fun acquireOpenReference(): Boolean = lock.withLock {
        if (evicted) {
            false
        } else {
            openReferences++
            true
        }
    }

    internal fun releaseOpenReference() {
        var shouldEvict = false
        lock.withLock {
            check(openReferences > 0)
            openReferences--
            if (currentMetadata.linkCount == 0u && openReferences == 0 && !evicted) {
                evicted = true
                shouldEvict = true
            }
        }
        if (shouldEvict) {
            backend.evict(this)
        }
    }
}

class Dentry internal constructor(
    val superBlock: SuperBlock,
    name: VfsName,
    parent: Dentry?,
    inode: Inode?,
) {
    private class CachedChild(
        val dentry: Dentry,
        var validity: CacheValidity,
        var reference: DentryReference?,
    )

    private val lock = IrqSpinLock()
    private var currentName = name
    private var currentParent = parent
    private var currentInode = inode
    private val children = mutableMapOf<VfsName, CachedChild>()

    val name: VfsName
        get() = lock.withLock { currentName }

    val parent: Dentry?
        get() = lock.withLock { currentParent }

    fun inode(): Inode? = lock.withLock { currentInode }

    internal fun cachedChild(name: VfsName): Dentry? = lock.withLock {
        val child = children[name] ?: return@withLock null
        child.dentry.takeIf { child.validity.isValid(TscClock.nanoTime()) }
    }

    internal fun cacheChild(name: VfsName, lookup: DirectoryLookup): Dentry {
        var retiredReference: DentryReference? = null
        var retiredDentry: Dentry? = null
        val result = lock.withLock {
            val inode = lookup.inode
            val cached = children[name]
            if (cached != null) {
                val current = cached.dentry.inode()
                if (inode == null || current == null || current.sameIdentity(inode)) {
                    if (inode == null && current != null) retiredDentry = cached.dentry
                    if (current !== inode) cached.dentry.install(inode)
                    if (cached.reference !== lookup.reference) {
                        retiredReference = cached.reference
                    }
                    cached.validity = lookup.validity
                    cached.reference = lookup.reference
                    return@withLock cached.dentry
                }
                retiredReference = cached.reference
                retiredDentry = cached.dentry
                cached.dentry.install(null)
            }
            val child = Dentry(superBlock, name, this, inode)
            children[name] = CachedChild(child, lookup.validity, lookup.reference)
            child
        }
        retiredReference?.release()
        retiredDentry?.releaseCachedChildren()
        return result
    }

    internal fun markChildNegative(name: VfsName, expected: Dentry) {
        var reference: DentryReference? = null
        var invalidated = false
        lock.withLock {
            val child = children[name]?.takeIf { it.dentry === expected } ?: return@withLock
            reference = child.reference
            child.reference = null
            child.validity = CacheValidity.Persistent
            child.dentry.install(null)
            invalidated = true
        }
        reference?.release()
        if (invalidated) expected.releaseCachedChildren()
    }

    internal fun invalidateNegativeChild(name: VfsName) {
        lock.withLock {
            val child = children[name] ?: return@withLock
            if (child.dentry.inode() == null) children.remove(name)
        }
    }

    internal fun renameChild(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        var retired: CachedChild? = null
        renameLock.withLock {
            if (this === targetParent) {
                lock.withLock {
                    retired = renameChildLocked(source, targetParent, targetName, exchange)
                }
            } else {
                lock.withLock {
                    targetParent.lock.withLock {
                        retired = renameChildLocked(source, targetParent, targetName, exchange)
                    }
                }
            }
        }
        retired?.reference?.release()
        retired?.dentry?.releaseCachedChildren()
    }

    private fun renameChildLocked(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ): CachedChild? {
        val sourceName = source.currentName
        val sourceChild = children[sourceName]?.takeIf { it.dentry === source }
            ?: CachedChild(source, CacheValidity.Persistent, null)
        if (children[sourceName]?.dentry === source) children.remove(sourceName)
        if (exchange == null) {
            val retired = targetParent.children.put(targetName, sourceChild)
            retired?.dentry?.install(null)
            source.relocate(targetParent, targetName)
            return retired
        } else {
            val exchangeChild = targetParent.children[targetName]
                ?.takeIf { it.dentry === exchange }
                ?: CachedChild(exchange, CacheValidity.Persistent, null)
            targetParent.children[targetName] = sourceChild
            children[sourceName] = exchangeChild
            exchange.relocate(this, sourceName)
        }
        source.relocate(targetParent, targetName)
        return null
    }

    private fun relocate(parent: Dentry, name: VfsName) = lock.withLock {
        currentParent = parent
        currentName = name
    }

    private fun install(inode: Inode?) {
        lock.withLock { currentInode = inode }
    }

    internal fun releaseCachedChildren() {
        val pending = ArrayDeque<Dentry>()
        pending.addLast(this)
        while (pending.isNotEmpty()) {
            val parent = pending.removeLast()
            val cached = parent.lock.withLock {
                parent.children.values.toList().also { parent.children.clear() }
            }
            cached.forEach { child ->
                child.reference?.release()
                pending.addLast(child.dentry)
            }
        }
    }

    private fun Inode.sameIdentity(other: Inode): Boolean =
        superBlock === other.superBlock && id == other.id && generation == other.generation

    private companion object {
        val renameLock = IrqSpinLock()
    }
}
