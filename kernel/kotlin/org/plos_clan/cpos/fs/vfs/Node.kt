package org.plos_clan.cpos.fs

import org.plos_clan.cpos.utils.IrqSpinLock

class Inode internal constructor(
    val id: InodeId,
    val superBlock: SuperBlock,
    internal val backend: InodeBackend,
    metadata: InodeMetadata,
) {
    private val lock = IrqSpinLock()
    private var currentMetadata = metadata
    private var extendedAttributes: MutableMap<ExtendedAttributeName, ByteArray>? = null
    private var openReferences = 0
    private var evicted = false

    val type: InodeType
        get() = backend.type

    fun metadata(): InodeMetadata = lock.withLock { currentMetadata }

    internal fun updateMetadata(
        timestamps: InodeTimestampUpdate = InodeTimestampEvent.STATUS_CHANGED,
        update: (InodeMetadata) -> InodeMetadata = { it },
    ) {
        var shouldEvict = false
        lock.withLock {
            if (!evicted) {
                currentMetadata = updateMetadataLocked(timestamps, update)
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
    private val lock = IrqSpinLock()
    private var currentName = name
    private var currentParent = parent
    private var currentInode = inode
    private val children = mutableMapOf<VfsName, Dentry>()

    val name: VfsName
        get() = lock.withLock { currentName }

    val parent: Dentry?
        get() = lock.withLock { currentParent }

    fun inode(): Inode? = lock.withLock { currentInode }

    internal fun cachedChild(name: VfsName): Dentry? = lock.withLock { children[name] }

    internal fun cacheChild(name: VfsName, inode: Inode?): Dentry = lock.withLock {
        children[name]?.let { cached ->
            val current = cached.inode()
            if (inode == null || current == null ||
                current.superBlock === inode.superBlock && current.id == inode.id
            ) {
                if (current !== inode) cached.install(inode)
                return@withLock cached
            }
            cached.install(null)
        }
        Dentry(superBlock, name, this, inode).also { children[name] = it }
    }

    internal fun markChildNegative(name: VfsName, expected: Dentry) {
        lock.withLock {
            children[name]?.takeIf { it === expected }?.install(null)
        }
    }

    internal fun invalidateNegativeChild(name: VfsName) {
        lock.withLock {
            val child = children[name] ?: return@withLock
            if (child.inode() == null) children.remove(name)
        }
    }

    internal fun renameChild(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        renameLock.withLock {
            if (this === targetParent) {
                lock.withLock { renameChildLocked(source, targetParent, targetName, exchange) }
            } else {
                lock.withLock {
                    targetParent.lock.withLock {
                        renameChildLocked(source, targetParent, targetName, exchange)
                    }
                }
            }
        }
    }

    private fun renameChildLocked(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        if (children[source.currentName] === source) {
            children.remove(source.currentName)
        }
        if (exchange == null) {
            targetParent.children.put(targetName, source)?.install(null)
        } else {
            targetParent.children[targetName] = source
            children[source.currentName] = exchange
            exchange.relocate(this, source.currentName)
        }
        source.relocate(targetParent, targetName)
    }

    private fun relocate(parent: Dentry, name: VfsName) = lock.withLock {
        currentParent = parent
        currentName = name
    }

    private fun install(inode: Inode?) {
        lock.withLock { currentInode = inode }
    }

    private companion object {
        val renameLock = IrqSpinLock()
    }
}
