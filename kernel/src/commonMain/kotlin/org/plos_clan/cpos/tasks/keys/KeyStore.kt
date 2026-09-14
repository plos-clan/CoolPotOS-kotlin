@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks.keys

import org.plos_clan.cpos.tasks.Credentials
import org.plos_clan.cpos.utils.Errno
import kotlin.concurrent.atomics.AtomicReference

internal class KeyFailure(val errno: Int) : RuntimeException()

internal class KeyStore(
    private val clock: () -> Long,
    val userQuota: Quota = Quota(200, 20_000),
    val rootQuota: Quota = Quota(1_000_000, 25_000_000),
    var persistentLifetime: Long = 3 * 24 * 60 * 60,
    var overflowGroupId: Int = 65534,
) {
    data class Quota(var keys: Int, var bytes: Long)

    enum class Permission(val mask: Int) {
        VIEW(1), READ(2), WRITE(4), SEARCH(8), LINK(16), SETATTR(32),
    }

    enum class Type(val label: String, val readable: Boolean) {
        USER("user", true), LOGON("logon", false), KEYRING("keyring", true);

        fun validate(description: String, payload: ByteArray) {
            if (description.isEmpty() || this == LOGON && description.indexOf(':') <= 0) {
                throw KeyFailure(Errno.EINVAL)
            }
            if (this == KEYRING) {
                if (description.startsWith('.')) throw KeyFailure(Errno.EPERM)
                if (payload.isNotEmpty()) throw KeyFailure(Errno.EINVAL)
            } else if (payload.isEmpty() || payload.size > 32767) {
                throw KeyFailure(Errno.EINVAL)
            }
        }

        companion object {
            fun from(name: String): Type {
                if (name.isEmpty()) throw KeyFailure(Errno.EINVAL)
                if (name.startsWith('.')) throw KeyFailure(Errno.EPERM)
                return entries.firstOrNull { it.label == name } ?: throw KeyFailure(Errno.ENODEV)
            }
        }
    }

    private data class Index(val type: Type, val description: String)

    private class Account {
        var keys = 0
        var bytes = 0L
    }

    private sealed class Key(
        val serial: Int,
        val index: Index,
        var uid: Int,
        var gid: Int,
        var permissions: Int,
        val charged: Boolean,
    ) {
        var references = 0
        var revoked = false
        var invalidated = false
        var expires = 0L
        abstract val payloadSize: Int
        val quotaSize: Long get() = index.description.length + 1L + payloadSize

        fun error(now: Long): Int = when {
            invalidated -> Errno.ENOKEY
            revoked -> Errno.EKEYREVOKED
            expires != 0L && now >= expires -> Errno.EKEYEXPIRED
            else -> 0
        }
    }

    private class PayloadKey(
        serial: Int, index: Index, uid: Int, gid: Int, permissions: Int,
        var payload: ByteArray,
    ) : Key(serial, index, uid, gid, permissions, true) {
        override val payloadSize: Int get() = payload.size
    }

    private class Keyring(
        serial: Int, description: String, uid: Int, gid: Int, permissions: Int, charged: Boolean,
    ) : Key(serial, Index(Type.KEYRING, description), uid, gid, permissions, charged) {
        val links = LinkedHashMap<Index, Key>()
        var restricted = false
        override val payloadSize: Int get() = links.size * Int.SIZE_BYTES
    }

    private class ProcessKeyring {
        var ring: Keyring? = null
        var users = 1
    }

    private val keys = HashMap<Int, Key>()
    private val names = HashMap<String, MutableSet<Keyring>>()
    private val accounts = HashMap<Int, Account>()
    private val userRings = HashMap<Int, Pair<Keyring, Keyring>>()
    private val persistentRings = HashMap<Int, Keyring>()
    private var nextSerial = 1

    inner class Context(val credentials: Credentials) {
        private var threadRing: Keyring? = null
        private var processRing: ProcessKeyring? = ProcessKeyring()
        private var sessionRing: Keyring? = null
        private val pendingSession = AtomicReference<Keyring?>(null)
        private var requestDefault = 0
        var privileged = false
        val hasPendingSession: Boolean get() = pendingSession.load() != null
        val closed: Boolean get() = processRing == null

        private val uid: Int get() = credentials.userIds.filesystem
        private val gid: Int get() = credentials.groupIds.filesystem

        fun fork(credentials: Credentials, sameProcess: Boolean): Context = Context(credentials).also {
            it.sessionRing = sessionRing?.also { key -> key.references++ }
            it.requestDefault = requestDefault
            if (sameProcess) {
                val shared = checkNotNull(processRing)
                shared.users++
                it.processRing = shared
            }
        }

        fun exec() {
            val shared = processRing ?: return
            release(threadRing)
            threadRing = null
            if (--shared.users == 0) release(shared.ring)
            processRing = ProcessKeyring()
        }

        fun close() {
            val shared = processRing ?: return
            processRing = null
            release(threadRing)
            threadRing = null
            if (--shared.users == 0) release(shared.ring)
            release(sessionRing)
            release(pendingSession.exchange(null))
            sessionRing = null
        }

        fun updateIdentity() {
            threadRing?.let { key ->
                changeOwner(key, uid, gid, enforceQuota = false)
            }
        }

        fun applyPendingSession() {
            val pending = pendingSession.exchange(null) ?: return
            release(sessionRing)
            sessionRing = pending
        }

        fun sessionToParent(parent: Context) {
            val ring = lookup(-3, Permission.LINK) as Keyring
            val users = parent.credentials.userIds
            val groups = parent.credentials.groupIds
            val effectiveUid = credentials.userIds.effective
            val effectiveGid = credentials.groupIds.effective
            if (users.real != effectiveUid || users.effective != effectiveUid || users.saved != effectiveUid ||
                groups.real != effectiveGid || groups.effective != effectiveGid || groups.saved != effectiveGid ||
                ring.uid != effectiveUid || parent.sessionRing?.uid?.let { it != effectiveUid } == true
            ) throw KeyFailure(Errno.EPERM)
            if (parent.sessionRing === ring) return
            ring.references++
            release(parent.pendingSession.exchange(ring))
        }

        fun getId(id: Int, create: Boolean): Int = lookup(id, Permission.SEARCH, create).serial

        fun joinSession(name: String?): Int {
            if (name?.startsWith('.') == true) throw KeyFailure(Errno.EPERM)
            val existing = name?.let { names[it] }?.firstOrNull {
                it.error(clock()) == 0 && permitted(it, Permission.SEARCH, false)
            }
            val ring = existing ?: newRing(name ?: "_ses", uid, gid,
                SESSION_PERMISSIONS or if (name == null) 0 else Permission.LINK.mask shl 16)
            ring.references++
            release(sessionRing)
            sessionRing = ring
            return ring.serial
        }

        fun add(type: Type, description: String, payload: ByteArray, destination: Int): Int {
            type.validate(description, payload)
            val ring = ring(destination, Permission.WRITE, true)
            if (ring.restricted) throw KeyFailure(Errno.EPERM)
            val index = Index(type, description)
            val existing = ring.links[index]
            if (existing is PayloadKey && !existing.revoked && !existing.invalidated) {
                requirePermission(existing, Permission.WRITE, possessed(ring))
                updatePayload(existing, payload)
                return existing.serial
            }
            val permissions = ((ALL and
                (if (type.readable) ALL else Permission.READ.mask.inv())) shl 24) or (Permission.VIEW.mask shl 16)
            val key = if (type == Type.KEYRING) {
                newRing(description, uid, gid, permissions)
            } else {
                val serial = allocateSerial()
                PayloadKey(serial, index, uid, gid, permissions, payload).also(::register)
            }
            key.references++
            try {
                link(key, ring)
                return key.serial
            } finally {
                release(key)
            }
        }

        fun update(id: Int, payload: ByteArray) {
            val key = lookup(id, Permission.WRITE) as? PayloadKey ?: throw KeyFailure(Errno.EOPNOTSUPP)
            key.index.type.validate(key.index.description, payload)
            updatePayload(key, payload)
        }

        fun describe(id: Int): ByteArray {
            val key = lookup(id, Permission.VIEW)
            val group = if (key.gid == -1) overflowGroupId else key.gid
            val text = "${key.index.type.label};${key.uid.toUInt()};${group.toUInt()};" +
                "${key.permissions.toUInt().toString(16).padStart(8, '0')};${key.index.description}\u0000"
            return ByteArray(text.length) { text[it].code.toByte() }
        }

        fun read(id: Int, bufferSize: ULong = 0uL): ByteArray {
            val key = lookup(id, null, invalidIdError = Errno.ENOKEY)
            val possessed = id in -5..-1 || possessed(key)
            if (!permitted(key, Permission.READ, possessed) && !possessed) {
                throw KeyFailure(Errno.EACCES)
            }
            if (!key.index.type.readable) throw KeyFailure(Errno.EOPNOTSUPP)
            if (key is PayloadKey) return key.payload
            key as Keyring
            if (bufferSize % Int.SIZE_BYTES.toULong() != 0uL) throw KeyFailure(Errno.EINVAL)
            val bytes = ByteArray(key.payloadSize)
            var offset = 0
            for (child in key.links.values) {
                repeat(Int.SIZE_BYTES) { bytes[offset++] = (child.serial ushr (it * 8)).toByte() }
            }
            return bytes
        }

        fun security(id: Int): ByteArray {
            lookup(id, Permission.VIEW)
            return byteArrayOf(0)
        }

        fun setPermissions(id: Int, permissions: Int) {
            if (permissions and VALID_PERMISSIONS.inv() != 0) throw KeyFailure(Errno.EINVAL)
            val key = lookup(id, Permission.SETATTR, true)
            if (!privileged && key.uid != uid) throw KeyFailure(Errno.EPERM)
            key.permissions = permissions
        }

        fun chown(id: Int, user: Int, group: Int) {
            if (user == -1 && group == -1) return
            val key = lookup(id, Permission.SETATTR, true)
            if (!privileged && (user != -1 && user != key.uid ||
                    group != -1 && group != key.gid && !inGroup(group))
            ) throw KeyFailure(Errno.EPERM)
            changeOwner(key, if (user == -1) key.uid else user, if (group == -1) key.gid else group)
        }

        fun revoke(id: Int) {
            val key = lookup(id, null)
            val possessed = possessed(key)
            if (!permitted(key, Permission.WRITE, possessed)) requirePermission(key, Permission.SETATTR, possessed)
            key.revoked = true
            discardPayload(key)
        }

        fun invalidate(id: Int) {
            val key = lookup(id, Permission.SEARCH)
            key.invalidated = true
            collect()
        }

        fun clear(id: Int) = discardPayload(ring(id, Permission.WRITE, true))

        fun link(id: Int, destination: Int) {
            val key = lookup(id, Permission.LINK, true)
            val destinationRing = ring(destination, Permission.WRITE, true)
            link(key, destinationRing)
        }

        fun unlink(id: Int, source: Int) {
            val key = lookup(id, null, validate = false)
            val ring = ring(source, Permission.WRITE)
            if (ring.links[key.index] !== key) throw KeyFailure(Errno.ENOENT)
            removeLink(ring, key)
        }

        fun move(id: Int, source: Int, destination: Int, flags: UInt) {
            if (flags and 1u.inv() != 0u) throw KeyFailure(Errno.EINVAL)
            val key = lookup(id, Permission.LINK, true)
            val from = ring(source, Permission.WRITE)
            val to = ring(destination, Permission.WRITE, true)
            if (from === to) return
            if (from.links[key.index] !== key) throw KeyFailure(Errno.ENOENT)
            if (flags != 0u && to.links.containsKey(key.index)) throw KeyFailure(Errno.EEXIST)
            link(key, to)
            removeLink(from, key)
        }

        fun search(id: Int, type: Type, description: String, destination: Int): Int {
            val ring = ring(id, Permission.SEARCH)
            val found = find(ring, Index(type, description), possessed(ring))
                ?: throw KeyFailure(Errno.ENOKEY)
            if (destination != 0) {
                requirePermission(found, Permission.LINK, possessed(ring))
                link(found, ring(destination, Permission.WRITE, true))
            }
            return found.serial
        }

        fun request(type: Type, description: String, destination: Int): Int {
            val target = if (destination == 0) null else ring(destination, Permission.WRITE, true)
            val index = Index(type, description)
            var failure = Errno.ENOKEY
            for (indexOfRoot in 0..2) {
                val root = when (indexOfRoot) {
                    0 -> threadRing
                    1 -> processRing?.ring
                    else -> sessionRing ?: userRings[uid]?.second
                }
                if (root == null || root.error(clock()) != 0 || !permitted(root, Permission.SEARCH, true)) continue
                val found = try {
                    find(root, index, true)
                } catch (error: KeyFailure) {
                    failure = error.errno
                    null
                } ?: continue
                if (target != null) {
                    requirePermission(found, Permission.LINK, true)
                    link(found, target)
                }
                return found.serial
            }
            throw KeyFailure(failure)
        }

        fun setRequestDefault(value: Int): Int {
            if (value !in -1..7 || value == 6) throw KeyFailure(Errno.EINVAL)
            if (value == 1 || value == 2) lookup(-value, null, true)
            val previous = requestDefault
            if (value != -1) requestDefault = value
            return previous
        }

        fun timeout(id: Int, seconds: UInt) {
            val key = lookup(id, Permission.SETATTR, true)
            key.expires = if (seconds == 0u) 0 else clock() + seconds.toLong()
        }

        fun restrict(id: Int, type: String?, restriction: String?) {
            val ring = ring(id, Permission.SETATTR)
            if (ring.restricted) throw KeyFailure(Errno.EEXIST)
            if (type != null) {
                Type.from(type)
                throw KeyFailure(Errno.ENOENT)
            }
            if (restriction != null) throw KeyFailure(Errno.EINVAL)
            ring.restricted = true
        }

        fun persistent(user: Int, destination: Int, mayChangeUser: Boolean = false): Int {
            val target = if (user == -1) credentials.userIds.real else user
            if (!mayChangeUser && target != credentials.userIds.real && target != credentials.userIds.effective) {
                throw KeyFailure(Errno.EPERM)
            }
            val destinationRing = ring(destination, Permission.WRITE, true)
            val ring = persistentRings[target]?.takeIf { it.error(clock()) == 0 } ?: run {
                release(persistentRings.remove(target))
                newRing("_persistent.${target.toUInt()}", target, -1, PERSISTENT_PERMISSIONS, false).also {
                    it.references++
                    persistentRings[target] = it
                }
            }
            link(ring, destinationRing)
            ring.expires = clock() + persistentLifetime
            return ring.serial
        }

        private fun lookup(
            id: Int, permission: Permission?, create: Boolean = false, validate: Boolean = true,
            invalidIdError: Int = Errno.EINVAL,
        ): Key {
            val key = when (id) {
                -1 -> threadRing ?: if (create) {
                    newRing("_tid", uid, gid, SPECIAL_PERMISSIONS).also { it.references++; threadRing = it }
                } else null
                -2 -> {
                    val shared = processRing ?: throw KeyFailure(Errno.ESRCH)
                    shared.ring ?: if (create) {
                        newRing("_pid", uid, gid, SPECIAL_PERMISSIONS).also { it.references++; shared.ring = it }
                    } else null
                }
                -3 -> sessionRing ?: run {
                    val ring = if (create) newRing("_ses", uid, gid, SESSION_PERMISSIONS) else users(uid).second
                    ring.references++
                    sessionRing = ring
                    ring
                }
                -4 -> users(uid).first
                -5 -> users(uid).second
                -6 -> throw KeyFailure(invalidIdError)
                -7, -8 -> null
                else -> if (id <= 0) throw KeyFailure(invalidIdError) else keys[id]
            } ?: throw KeyFailure(Errno.ENOKEY)
            if (key.invalidated) throw KeyFailure(Errno.ENOKEY)
            if (validate) {
                val error = key.error(clock())
                if (error != 0) throw KeyFailure(error)
            }
            if (permission != null && !permitted(key, permission, false)) {
                requirePermission(key, permission, id in -5..-1 || possessed(key))
            }
            return key
        }

        private fun ring(id: Int, permission: Permission, create: Boolean = false): Keyring =
            lookup(id, permission, create) as? Keyring ?: throw KeyFailure(Errno.ENOTDIR)

        private fun inGroup(group: Int): Boolean = group == gid || group in credentials.supplementaryGroups

        private fun permitted(key: Key, permission: Permission, possessed: Boolean): Boolean {
            val shift = when {
                key.uid == uid -> 16
                key.gid != -1 && key.permissions and 0x3f00 != 0 && inGroup(key.gid) -> 8
                else -> 0
            }
            val grants = (key.permissions ushr shift) or (if (possessed) key.permissions ushr 24 else 0)
            return grants and permission.mask != 0
        }

        private fun requirePermission(key: Key, permission: Permission, possessed: Boolean) {
            if (!permitted(key, permission, possessed)) throw KeyFailure(Errno.EACCES)
        }

        private fun possessed(key: Key): Boolean {
            for (index in 0..2) {
                val root = when (index) {
                    0 -> threadRing
                    1 -> processRing?.ring
                    else -> sessionRing ?: userRings[uid]?.second
                }
                if (root === key) return true
                if (root == null || root.error(clock()) != 0 || !permitted(root, Permission.SEARCH, true)) continue
                if (find(root, key.index, true, key) === key) return true
            }
            return false
        }

        private fun find(root: Keyring, index: Index, possessed: Boolean, exact: Key? = null): Key? {
            val queue = ArrayDeque<Keyring>()
            val visited = HashSet<Keyring>()
            queue.add(root)
            var failure = 0
            while (queue.isNotEmpty()) {
                val ring = queue.removeFirst()
                if (!visited.add(ring)) continue
                val candidate = if (ring.index == index) ring else ring.links[index]
                if (candidate != null && (exact == null || candidate === exact)) {
                    val error = candidate.error(clock())
                    if (error == 0 && permitted(candidate, Permission.SEARCH, possessed)) return candidate
                    failure = if (error == 0) Errno.EACCES else error
                }
                for (child in ring.links.values) {
                    if (child is Keyring && child.error(clock()) == 0 && permitted(child, Permission.SEARCH, possessed)) {
                        queue.add(child)
                    }
                }
            }
            if (exact == null && failure != 0) throw KeyFailure(failure)
            return null
        }
    }

    private fun allocateSerial(): Int {
        val start = nextSerial
        do {
            val candidate = nextSerial
            nextSerial = if (nextSerial == Int.MAX_VALUE) 1 else nextSerial + 1
            if (!keys.containsKey(candidate)) return candidate
        } while (nextSerial != start)
        throw KeyFailure(Errno.ENFILE)
    }

    private fun register(key: Key) {
        if (key.charged) charge(key.uid, 1, key.quotaSize)
        keys[key.serial] = key
        if (key is Keyring && !key.index.description.startsWith('.')) {
            names.getOrPut(key.index.description) { LinkedHashSet() }.add(key)
        }
    }

    private fun newRing(
        description: String, uid: Int, gid: Int, permissions: Int, charged: Boolean = true,
    ): Keyring = Keyring(allocateSerial(), description, uid, gid, permissions, charged).also(::register)

    private fun users(uid: Int): Pair<Keyring, Keyring> = userRings.getOrPut(uid) {
        val user = newRing("_uid.${uid.toUInt()}", uid, -1, USER_PERMISSIONS, false)
        val session = newRing("_uid_ses.${uid.toUInt()}", uid, -1, USER_PERMISSIONS, false)
        user.references++
        session.references++
        link(user, session)
        user to session
    }

    private fun charge(uid: Int, count: Int, bytes: Long, enforceQuota: Boolean = true) {
        val account = accounts[uid] ?: Account()
        val quota = if (uid == 0) rootQuota else userQuota
        if (enforceQuota && (count > 0 && account.keys.toLong() + count > quota.keys ||
            bytes > 0 && account.bytes + bytes > quota.bytes)
        ) throw KeyFailure(Errno.EDQUOT)
        account.keys += count
        account.bytes += bytes
        if (account.keys == 0 && account.bytes == 0L) accounts.remove(uid) else accounts[uid] = account
    }

    private fun changeOwner(key: Key, uid: Int, gid: Int, enforceQuota: Boolean = true) {
        if (uid != key.uid && key.charged) {
            charge(uid, 1, key.quotaSize, enforceQuota)
            charge(key.uid, -1, -key.quotaSize)
        }
        key.uid = uid
        key.gid = gid
    }

    private fun updatePayload(key: PayloadKey, payload: ByteArray) {
        charge(key.uid, 0, payload.size.toLong() - key.payloadSize)
        key.payload = payload
        key.expires = 0
    }

    private fun link(key: Key, destination: Keyring) {
        if (destination.restricted) throw KeyFailure(Errno.EPERM)
        if (key === destination) throw KeyFailure(Errno.EDEADLK)
        if (key is Keyring) {
            val queue = ArrayDeque<Keyring>()
            val visited = HashSet<Keyring>()
            queue.add(key)
            while (queue.isNotEmpty()) {
                val ring = queue.removeFirst()
                if (!visited.add(ring)) continue
                if (ring === destination) throw KeyFailure(Errno.EDEADLK)
                for (child in ring.links.values) if (child is Keyring) queue.add(child)
            }
        }
        val previous = destination.links[key.index]
        if (previous === key) return
        if (previous == null && destination.charged) charge(destination.uid, 0, Int.SIZE_BYTES.toLong())
        key.references++
        destination.links[key.index] = key
        release(previous)
    }

    private fun removeLink(ring: Keyring, key: Key) {
        ring.links.remove(key.index)
        if (ring.charged) charge(ring.uid, 0, -Int.SIZE_BYTES.toLong())
        release(key)
    }

    private fun discardPayload(key: Key) {
        if (key.charged) charge(key.uid, 0, -key.payloadSize.toLong())
        when (key) {
            is PayloadKey -> key.payload = byteArrayOf()
            is Keyring -> {
                key.links.values.forEach(::release)
                key.links.clear()
            }
        }
    }

    private fun release(key: Key?) {
        if (key == null || --key.references != 0) return
        val pending = ArrayDeque<Key>()
        pending.add(key)
        while (pending.isNotEmpty()) {
            val released = pending.removeLast()
            keys.remove(released.serial)
            if (released.charged) charge(released.uid, -1, -released.quotaSize)
            when (released) {
                is PayloadKey -> released.payload = byteArrayOf()
                is Keyring -> {
                    for (child in released.links.values) if (--child.references == 0) pending.add(child)
                    released.links.clear()
                    names[released.index.description]?.let {
                        it.remove(released)
                        if (it.isEmpty()) names.remove(released.index.description)
                    }
                }
            }
        }
    }

    fun collect() {
        val now = clock()
        val persistent = persistentRings.iterator()
        while (persistent.hasNext()) {
            val ring = persistent.next().value
            if (ring.error(now) == 0) continue
            persistent.remove()
            release(ring)
        }
        for (key in keys.values.toList()) {
            if (key !is Keyring || key.references == 0) continue
            for (child in key.links.values.toList()) {
                if (child.invalidated || child.revoked || child.expires != 0L && now - child.expires >= GC_DELAY) {
                    removeLink(key, child)
                }
            }
        }
    }

    companion object {
        private const val ALL = 0x3f
        private const val VALID_PERMISSIONS = 0x3f3f3f3f
        private const val SPECIAL_PERMISSIONS = 0x3f010000
        private const val SESSION_PERMISSIONS = 0x3f030000
        private const val USER_PERMISSIONS = 0x1f3f0000
        private const val PERSISTENT_PERMISSIONS = 0x1f030000
        private const val GC_DELAY = 300
        val capabilities: ByteArray get() = byteArrayOf(0xe3.toByte(), 0)
    }
}
