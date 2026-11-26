@file:OptIn(ExperimentalAtomicApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package org.plos_clan.cpos.fs.vfs

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import org.plos_clan.cpos.drivers.RealtimeClock
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.native.ref.createCleaner

class Inode internal constructor(
    val id: InodeId,
    val superBlock: SuperBlock,
    internal val backend: InodeBackend,
    initialAttributes: InodeAttributeSnapshot,
    val generation: ULong = 0uL,
    resource: AutoCloseable? = null,
    private val owner: InodeOwner = superBlock.backend,
) {
    private val cleanup = resource?.let { createCleaner(it) { resource -> resource.close() } }
    private val lock = IrqSpinLock()
    @Volatile private var attributeSnapshot = initialAttributes
    private val currentMetadata get() = attributeSnapshot.attributes.metadata
    private var attributeGeneration = 0uL
    private var extendedAttributes: MutableMap<ExtendedAttributeName, ByteArray>? = null
    private var accessAcl: PosixAcl? = null
    private val observers = AtomicReference<List<InodeObserver>?>(null)
    private var openReferences = 0
    private var evicted = false

    val type: InodeType
        get() = backend.type

    internal fun sameIdentity(other: Inode): Boolean =
        superBlock === other.superBlock && id == other.id && generation == other.generation

    fun metadata(): InodeMetadata = currentMetadata

    fun attributes(
        caller: VfsOperationContext,
        forceRefresh: Boolean = false,
        cachedOnly: Boolean = false,
    ): VfsResult<InodeAttributes> {
        val cached = attributeSnapshot
        if (!forceRefresh && cached.validity.isValid(TscClock)) return cached.result
        if (cachedOnly) return VfsResult.Err(VfsError.WOULD_BLOCK)
        val request = lock.withLock {
            ++attributeGeneration to currentMetadata
        }
        val loaded = when (val result = backend.loadAttributes(caller, this)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        return lock.withLock {
            if (evicted || currentMetadata != request.second) {
                val merged = loaded.attributes.copy(metadata = currentMetadata)
                return@withLock VfsResult.Ok(merged)
            }
            if (request.first == attributeGeneration) attributeSnapshot = loaded
            loaded.result
        }
    }

    internal fun installAttributeSnapshot(snapshot: InodeAttributeSnapshot) = lock.withLock {
        if (evicted) return@withLock
        attributeSnapshot = snapshot
        attributeGeneration++
    }

    internal fun invalidateAttributes() = lock.withLock {
        if (evicted) return@withLock
        attributeSnapshot = attributeSnapshot.copy(validity = CacheValidity.Volatile)
        attributeGeneration++
    }

    internal fun updateCachedTimestamps(update: InodeTimestampUpdate) = lock.withLock {
        if (evicted || !update.requiresCurrentTime) return@withLock
        val timestamps = update.apply(currentMetadata.timestamps, RealtimeClock.now())
        if (timestamps == currentMetadata.timestamps) return@withLock
        val updated = currentMetadata.copy(timestamps = timestamps)
        attributeSnapshot = attributeSnapshot.withMetadata(updated)
        attributeGeneration++
    }

    internal fun attributeVersion(): ULong = lock.withLock { attributeGeneration }

    internal fun shrinkCachedSize(size: ULong, expectedVersion: ULong) = lock.withLock {
        if (evicted || attributeGeneration != expectedVersion || size >= currentMetadata.size) {
            return@withLock
        }
        val updated = currentMetadata.copy(size = size)
        attributeSnapshot = attributeSnapshot.withMetadata(updated)
        attributeGeneration++
    }

    internal fun updateMetadata(
        timestamps: InodeTimestampUpdate = InodeTimestampEvent.STATUS_CHANGED,
        update: (InodeMetadata) -> InodeMetadata = { it },
    ) {
        var shouldEvict = false
        var lostLastLink = false
        lock.withLock {
            if (!evicted) {
                val previousLinks = currentMetadata.linkCount
                val updated = updateMetadataLocked(timestamps, update = update)
                attributeSnapshot = attributeSnapshot.withMetadata(updated, CacheValidity.Volatile)
                attributeGeneration++
                lostLastLink = previousLinks != 0u && currentMetadata.linkCount == 0u
                if (currentMetadata.linkCount == 0u && openReferences == 0) {
                    evicted = true
                    shouldEvict = true
                }
            }
        }
        if (lostLastLink && type != InodeType.DIRECTORY) {
            notify(FileSystemEvent.ATTRIBUTES_CHANGED)
        }
        if (shouldEvict) {
            removeObservers(InodeObserverRemoval.DELETED)
            owner.evict(this)
        }
    }

    internal fun aclAccess(caller: VfsOperationContext, requested: AccessPermissions): Boolean? =
        lock.withLock { accessAcl?.permits(caller, currentMetadata, requested) }

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
        val acl = if (name == PosixAcl.ACCESS) {
            PosixAcl.parse(value) ?: return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
        } else null
        if (name == PosixAcl.DEFAULT) return@withLock VfsResult.Err(VfsError.NOT_SUPPORTED)
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
        val base = if (acl != null) {
            accessAcl = acl
            currentMetadata.copy(mode = acl.mode(currentMetadata.mode))
        } else currentMetadata
        val updated = updateMetadataLocked(InodeTimestampEvent.STATUS_CHANGED, base)
        attributeSnapshot = attributeSnapshot.withMetadata(updated, CacheValidity.Volatile)
        attributeGeneration++
        VfsResult.Ok(Unit)
    }

    internal fun removeExtendedAttribute(name: ExtendedAttributeName): VfsResult<Unit> =
        lock.withLock {
            val attributes = extendedAttributes
            if (attributes?.remove(name) == null) {
                return@withLock VfsResult.Err(VfsError.NO_DATA)
            }
            if (name == PosixAcl.ACCESS) accessAcl = null
            if (attributes.isEmpty()) extendedAttributes = null
            val updated = updateMetadataLocked(InodeTimestampEvent.STATUS_CHANGED)
            attributeSnapshot = attributeSnapshot.withMetadata(updated, CacheValidity.Volatile)
            attributeGeneration++
            VfsResult.Ok(Unit)
        }

    private fun updateMetadataLocked(
        timestamps: InodeTimestampUpdate,
        base: InodeMetadata = currentMetadata,
        update: (InodeMetadata) -> InodeMetadata = { it },
    ): InodeMetadata {
        val metadata = update(base)
        val acl = accessAcl
        if (acl != null && metadata.mode != base.mode) {
            val updated = acl.chmod(metadata.mode)
            accessAcl = updated
            extendedAttributes?.set(PosixAcl.ACCESS, updated.bytes())
        }
        if (!timestamps.requiresCurrentTime) return metadata
        val updatedTimestamps = timestamps.apply(metadata.timestamps, RealtimeClock.now())
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
            removeObservers(InodeObserverRemoval.DELETED)
            owner.evict(this)
        }
    }

    internal fun observe(observer: InodeObserver): Boolean = lock.withLock {
        if (evicted) return@withLock false
        val current = observers.load()
        if (current != null) {
            observers.store(current + observer)
            FileSystemEventObservers.added()
            return@withLock true
        }
        try {
            if (!superBlock.trackObservedInode(this)) return@withLock false
            observers.store(listOf(observer))
            FileSystemEventObservers.added()
        } catch (error: OutOfMemoryError) {
            superBlock.stopTrackingObservedInode(this)
            throw error
        }
        true
    }

    internal fun stopObserving(observer: InodeObserver) {
        val untrack = lock.withLock {
            val current = observers.load() ?: return
            val index = current.indexOfFirst { it === observer }
            if (index < 0) return
            if (current.size == 1) {
                observers.store(null)
                true
            } else {
                observers.store(current.toMutableList().also { it.removeAt(index) })
                false
            }
        }
        FileSystemEventObservers.removed()
        if (untrack) superBlock.stopTrackingObservedInode(this)
    }

    internal fun notify(
        event: FileSystemEvent,
        name: VfsName? = null,
        cookie: UInt = 0u,
        subject: Inode = this,
        unlinked: Boolean = false,
    ) {
        val current = observers.load() ?: return
        val notification = FileSystemNotification(
            event = event,
            name = name,
            cookie = cookie,
            directory = subject.type == InodeType.DIRECTORY,
            unlinked = unlinked,
        )
        current.forEach { it.notify(notification) }
    }

    internal fun removeObservers(reason: InodeObserverRemoval, tracked: Boolean = true) {
        val removed = lock.withLock {
            observers.exchange(null).orEmpty()
        }
        if (removed.isEmpty()) return
        FileSystemEventObservers.removed(removed.size)
        if (tracked) superBlock.stopTrackingObservedInode(this)
        removed.forEach { it.removed(reason) }
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
        val validity: CacheValidity,
        val reference: DentryReference?,
    ) {
        val result = if (dentry.inode() == null) VfsResult.Err(VfsError.NOT_FOUND)
            else VfsResult.Ok(dentry)

        fun release() {
            reference?.release()
            dentry.releaseCachedChildren()
        }
    }

    private val lock = IrqSpinLock()
    internal data class Snapshot(
        val name: VfsName,
        val parent: Dentry?,
        val inode: Inode?,
        val unlinked: Boolean = false,
    )

    private val state = AtomicReference(Snapshot(name, parent, inode))
    internal val snapshot: Snapshot get() = state.load()
    @Volatile private var publishedChildren: PersistentMap<VfsName, CachedChild>? = persistentHashMapOf()
    private var children: PersistentMap<VfsName, CachedChild>
        get() = publishedChildren ?: lock.withLock { checkNotNull(publishedChildren) }
        set(value) { publishedChildren = value }

    val name: VfsName
        get() = snapshot.name

    val parent: Dentry?
        get() = snapshot.parent

    internal fun isDescendantOf(ancestor: Dentry): Boolean {
        var current: Dentry? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    fun inode(): Inode? = snapshot.inode

    val isUnlinked: Boolean
        get() = snapshot.unlinked

    internal fun lookupChild(
        caller: VfsOperationContext,
        name: VfsName,
        cachedOnly: Boolean = false,
        covered: Dentry? = null,
    ): VfsResult<Dentry> {
        val directory = inode() ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val backend = directory.backend as? DirectoryBackend
            ?: return VfsResult.Err(VfsError.NOT_DIRECTORY)
        when (val access = backend.checkAccess(
            caller,
            directory,
            AccessPermissions.EXECUTE,
            cachedOnly,
        )) {
            is VfsResult.Ok -> Unit
            is VfsResult.Err -> return access
        }
        if (covered != null) return VfsResult.Ok(covered)
        val cached = children[name]
        if (cached != null && cached.validity.isValid(TscClock)) return cached.result
        if (cachedOnly) return VfsResult.Err(VfsError.WOULD_BLOCK)
        val lookup = when (val result = backend.lookup(caller, directory, name)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return result
        }
        val child = cacheChild(name, lookup)
        return if (lookup.inode != null) VfsResult.Ok(child)
        else VfsResult.Err(VfsError.NOT_FOUND)
    }

    internal fun cacheChild(name: VfsName, lookup: DirectoryLookup): Dentry {
        var retiredReference: DentryReference? = null
        var retiredDentry: Dentry? = null
        val result = lock.withLock {
            val inode = lookup.inode
            val cached = children[name]
            if (cached != null) {
                val current = cached.dentry.inode()
                val sameInode = current === inode ||
                    current != null && inode != null && current.sameIdentity(inode)
                if (sameInode) {
                    if (current !== inode) cached.dentry.install(inode)
                    if (cached.reference !== lookup.reference) retiredReference = cached.reference
                    if (cached.reference !== lookup.reference || cached.validity !== lookup.validity) {
                        val updated = CachedChild(cached.dentry, lookup.validity, lookup.reference)
                        children = children.putting(name, updated)
                    }
                    return@withLock cached.dentry
                }
                retiredReference = cached.reference
                retiredDentry = cached.dentry
                cached.dentry.detach()
            }
            val child = Dentry(superBlock, name, this, inode)
            val entry = CachedChild(child, lookup.validity, lookup.reference)
            children = children.putting(name, entry)
            child
        }
        retiredReference?.release()
        retiredDentry?.releaseCachedChildren()
        return result
    }

    internal fun markChildNegative(name: VfsName, expected: Dentry) {
        val retired = lock.withLock {
            val child = children[name] ?: return
            if (child.dentry !== expected) return
            val negative = Dentry(superBlock, name, this, null)
            val entry = CachedChild(negative, CacheValidity.Persistent, null)
            val updated = children.putting(name, entry)
            expected.detach()
            children = updated
            child
        }
        retired.release()
    }

    internal fun invalidateNegativeChild(name: VfsName) {
        lock.withLock {
            val child = children[name] ?: return@withLock
            if (child.dentry.inode() == null) children = children.removing(name)
        }
    }

    internal fun notifyParent(subject: Inode, event: FileSystemEvent, cookie: UInt) {
        val snapshot = this.snapshot
        snapshot.parent?.inode()?.notify(
            event = event,
            name = snapshot.name,
            cookie = cookie,
            subject = subject,
            unlinked = snapshot.unlinked || snapshot.inode?.sameIdentity(subject) != true,
        )
    }

    internal fun renameChild(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ) {
        val retired = lock.withLock(targetParent.lock) {
            renameChildLocked(source, targetParent, targetName, exchange)
        }
        retired?.release()
    }

    private fun renameChildLocked(
        source: Dentry,
        targetParent: Dentry,
        targetName: VfsName,
        exchange: Dentry?,
    ): CachedChild? {
        val sourceName = source.name
        val original = children
        val target = targetParent.children
        val previous = original[sourceName]
        val sourceChild = previous?.takeIf { it.dentry === source }
            ?: CachedChild(source, CacheValidity.Persistent, null)
        val replaced = target[targetName]
        val from = if (exchange != null) {
            val exchangeChild = replaced?.takeIf { it.dentry === exchange }
                ?: CachedChild(exchange, CacheValidity.Persistent, null)
            original.putting(sourceName, exchangeChild)
        } else if (previous === sourceChild) original.removing(sourceName) else original
        val destination = if (this === targetParent) from else target
        val moved = destination.putting(targetName, sourceChild)
        val retired = replaced.takeIf { exchange == null && it !== sourceChild }
        retired?.dentry?.detach()
        exchange?.relocate(this, sourceName)
        source.relocate(targetParent, targetName)
        if (this !== targetParent) {
            targetParent.publishedChildren = null
            children = from
        }
        targetParent.children = moved
        return retired
    }

    private fun relocate(parent: Dentry, name: VfsName) =
        state.update { it.copy(parent = parent, name = name) }

    private fun install(inode: Inode?) {
        state.update { it.copy(inode = inode) }
    }

    private fun detach() {
        state.update { it.copy(unlinked = true) }
    }

    internal fun releaseCachedChildren() {
        val pending = ArrayDeque<Dentry>()
        pending.addLast(this)
        while (pending.isNotEmpty()) {
            val parent = pending.removeLast()
            val cached = parent.lock.withLock {
                parent.children.also { parent.children = it.cleared() }
            }
            cached.values.forEach { child ->
                child.reference?.release()
                pending.addLast(child.dentry)
            }
        }
    }

}
