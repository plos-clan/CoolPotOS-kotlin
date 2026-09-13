package org.plos_clan.cpos.tasks.keys

import org.plos_clan.cpos.tasks.Credentials
import org.plos_clan.cpos.utils.Errno
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyStoreTest {
    private var now = 100L
    private val store = KeyStore({ now })
    private val credentials = Credentials(Credentials.Identity(1000, 1000, 1000), Credentials.Identity(100, 100, 100))
    private val owner = store.Context(credentials)
    private val payload = byteArrayOf(1, 0, -1, 42)

    @Test
    fun createsAndUpdatesBinaryKeysWithLinuxDefaults() {
        val session = owner.joinSession(null)
        assertEquals("keyring;1000;100;3f030000;_ses\u0000", owner.describe(session).decodeToString())
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        assertEquals("user;1000;100;3f010000;token\u0000", owner.describe(key).decodeToString())
        assertContentEquals(payload, owner.read(key))
        assertEquals(key, owner.add(KeyStore.Type.USER, "token", byteArrayOf(9), session))
        assertContentEquals(byteArrayOf(9), owner.read(key))
        assertEquals(key, owner.request(KeyStore.Type.USER, "token", 0))
        assertEquals(key, owner.getId(key, false))
        assertEquals(Int.SIZE_BYTES, owner.read(session).size)
    }

    @Test
    fun validatesTypesAndNeverReadsLogonPayloads() {
        val session = owner.joinSession(null)
        failure(Errno.EINVAL) { KeyStore.Type.from("") }
        failure(Errno.EPERM) { KeyStore.Type.from(".internal") }
        failure(Errno.ENODEV) { KeyStore.Type.from("unknown") }
        failure(Errno.EINVAL) { owner.add(KeyStore.Type.USER, "", payload, session) }
        failure(Errno.EINVAL) { owner.add(KeyStore.Type.USER, "token", byteArrayOf(), session) }
        failure(Errno.EINVAL) { owner.add(KeyStore.Type.LOGON, "token", payload, session) }
        failure(Errno.EINVAL) { owner.add(KeyStore.Type.KEYRING, "ring", payload, session) }
        failure(Errno.EPERM) { owner.add(KeyStore.Type.KEYRING, ".ring", byteArrayOf(), session) }
        val key = owner.add(KeyStore.Type.LOGON, "service:token", payload, session)
        assertEquals("logon;1000;100;3d010000;service:token\u0000", owner.describe(key).decodeToString())
        owner.setPermissions(key, 0x3f3f3f3f)
        failure(Errno.EOPNOTSUPP) { owner.read(key) }
        owner.update(key, byteArrayOf(2))
    }

    @Test
    fun combinesPossessionWithExactlyOneIdentityClass() {
        val session = owner.joinSession(null)
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        owner.setPermissions(key, 0x3f010800)
        val group = store.Context(Credentials(Credentials.Identity(1001, 1001, 1001), credentials.groupIds))
        assertEquals(key, group.getId(key, false))
        failure(Errno.EACCES) { group.read(key) }
        val sameUser = store.Context(credentials)
        failure(Errno.EACCES) { sameUser.getId(key, false) }
        owner.setPermissions(key, 0x3d010000)
        assertContentEquals(payload, owner.read(key))
        failure(Errno.EACCES) { sameUser.read(key) }
        val root = store.Context(Credentials()).apply { privileged = true }
        failure(Errno.EACCES) { root.read(key) }
    }

    @Test
    fun searchesOnlySearchablePathsAndPreservesIdentityWithinSharedGraphs() {
        val session = owner.joinSession(null)
        val ring = owner.add(KeyStore.Type.KEYRING, "nested", byteArrayOf(), session)
        val key = owner.add(KeyStore.Type.USER, "token", payload, ring)
        assertEquals(key, owner.search(session, KeyStore.Type.USER, "token", 0))
        owner.setPermissions(key, 0x37210000)
        failure(Errno.EACCES) { owner.search(session, KeyStore.Type.USER, "token", 0) }
        failure(Errno.EACCES) { owner.request(KeyStore.Type.USER, "token", 0) }
        owner.setPermissions(key, 0x3f010000)
        owner.setPermissions(ring, 0x37210000)
        failure(Errno.ENOKEY) { owner.search(session, KeyStore.Type.USER, "token", 0) }
        failure(Errno.EACCES) { owner.read(key) }
        owner.setPermissions(ring, 0x3f010000)
        assertContentEquals(payload, owner.read(key))
        val other = owner.add(KeyStore.Type.KEYRING, "other", byteArrayOf(), session)
        owner.link(ring, other)
        owner.unlink(ring, session)
        assertEquals(key, owner.request(KeyStore.Type.USER, "token", 0))
    }

    @Test
    fun replacesLinksByTypeAndDescriptionAndRejectsCycles() {
        val session = owner.joinSession(null)
        val first = owner.add(KeyStore.Type.KEYRING, "first", byteArrayOf(), session)
        val second = owner.add(KeyStore.Type.KEYRING, "second", byteArrayOf(), session)
        val old = owner.add(KeyStore.Type.USER, "token", payload, first)
        val replacement = owner.add(KeyStore.Type.USER, "token", byteArrayOf(2), second)
        owner.link(replacement, first)
        assertEquals(replacement, owner.search(first, KeyStore.Type.USER, "token", 0))
        failure(Errno.ENOKEY) { owner.read(old) }
        owner.link(first, second)
        failure(Errno.EDEADLK) { owner.link(second, first) }
        failure(Errno.EDEADLK) { owner.link(session, session) }
        assertEquals(replacement, owner.search(first, KeyStore.Type.USER, "token", 0))
    }

    @Test
    fun movesAtomicallyAndPreservesBothRingsOnFailure() {
        val session = owner.joinSession(null)
        val from = owner.add(KeyStore.Type.KEYRING, "from", byteArrayOf(), session)
        val to = owner.add(KeyStore.Type.KEYRING, "to", byteArrayOf(), session)
        val key = owner.add(KeyStore.Type.USER, "token", payload, from)
        val displaced = owner.add(KeyStore.Type.USER, "token", byteArrayOf(3), to)
        failure(Errno.EINVAL) { owner.move(key, from, to, 2u) }
        failure(Errno.EEXIST) { owner.move(key, from, to, 1u) }
        assertEquals(key, owner.search(from, KeyStore.Type.USER, "token", 0))
        assertEquals(displaced, owner.search(to, KeyStore.Type.USER, "token", 0))
        owner.move(key, from, to, 0u)
        assertEquals(key, owner.search(to, KeyStore.Type.USER, "token", 0))
        failure(Errno.ENOKEY) { owner.search(from, KeyStore.Type.USER, "token", 0) }
        failure(Errno.ENOKEY) { owner.read(displaced) }
        failure(Errno.ENOENT) { owner.move(key, from, to, 0u) }
    }

    @Test
    fun restrictionsArePermanentAndDoNotBlockExistingPayloadUpdates() {
        val session = owner.joinSession(null)
        val ring = owner.add(KeyStore.Type.KEYRING, "sealed", byteArrayOf(), session)
        val key = owner.add(KeyStore.Type.USER, "token", payload, ring)
        failure(Errno.ENOENT) { owner.restrict(ring, "user", "anything") }
        owner.restrict(ring, null, null)
        failure(Errno.EEXIST) { owner.restrict(ring, null, null) }
        failure(Errno.EPERM) { owner.link(key, ring) }
        failure(Errno.EPERM) { owner.add(KeyStore.Type.USER, "other", payload, ring) }
        owner.update(key, byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), owner.read(key))
        owner.clear(ring)
        failure(Errno.ENOKEY) { owner.read(key) }
    }

    @Test
    fun distinguishesRevocationExpirationInvalidationAndMissingKeys() {
        val session = owner.joinSession(null)
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        owner.timeout(key, 2u)
        now++
        assertContentEquals(payload, owner.read(key))
        now++
        failure(Errno.EKEYEXPIRED) { owner.read(key) }
        failure(Errno.EKEYEXPIRED) { owner.timeout(key, 10u) }
        failure(Errno.EKEYEXPIRED) { owner.search(session, KeyStore.Type.USER, "token", 0) }
        failure(Errno.EKEYEXPIRED) { owner.invalidate(key) }
        val renewed = owner.add(KeyStore.Type.USER, "token", payload, session)
        assertEquals(key, renewed)
        owner.revoke(renewed)
        failure(Errno.EKEYREVOKED) { owner.read(renewed) }
        failure(Errno.EKEYREVOKED) { owner.revoke(renewed) }
        owner.unlink(renewed, session)
        val invalidated = owner.add(KeyStore.Type.USER, "token", payload, session)
        owner.invalidate(invalidated)
        failure(Errno.ENOKEY) { owner.read(invalidated) }
        assertTrue(owner.read(session).isEmpty())
    }

    @Test
    fun expirationGarbageCollectionReleasesPayloadsAndQuota() {
        val session = owner.joinSession(null)
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        owner.timeout(key, 1u)
        now += 301
        store.collect()
        assertTrue(owner.read(session).isEmpty())
        failure(Errno.ENOKEY) { owner.read(key) }
        owner.add(KeyStore.Type.USER, "token", payload, session)
    }

    @Test
    fun enforcesQuotaWithoutLeavingPartialLinksOrCharges() {
        val session = owner.joinSession(null)
        store.userQuota.keys = 2
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        failure(Errno.EDQUOT) { owner.add(KeyStore.Type.USER, "other", payload, session) }
        assertEquals(Int.SIZE_BYTES, owner.read(session).size)
        owner.unlink(key, session)
        val replacement = owner.add(KeyStore.Type.USER, "other", payload, session)
        store.userQuota.bytes = 20
        failure(Errno.EDQUOT) { owner.update(replacement, ByteArray(100)) }
        assertContentEquals(payload, owner.read(replacement))
        owner.clear(session)
        owner.add(KeyStore.Type.USER, "token", payload, session)
    }

    @Test
    fun checksOwnershipAndGroupMembershipSeparatelyFromSetattr() {
        val session = owner.joinSession(null)
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        failure(Errno.EINVAL) { owner.setPermissions(key, -1) }
        failure(Errno.EPERM) { owner.chown(key, 1001, -1) }
        failure(Errno.EPERM) { owner.chown(key, -1, 200) }
        credentials.replaceSupplementaryGroups(listOf(200))
        owner.chown(key, -1, 200)
        assertTrue(owner.describe(key).decodeToString().startsWith("user;1000;200;"))
        owner.privileged = true
        owner.chown(key, 1001, -1)
        owner.setPermissions(key, 0x3f010000)
        owner.privileged = false
        failure(Errno.EPERM) { owner.setPermissions(key, 0x3f3f0000) }
        owner.privileged = true
        owner.chown(key, -2, -2)
        assertTrue(owner.describe(key).decodeToString().startsWith("user;4294967294;4294967294;"))
    }

    @Test
    fun clonesShareOnlyTheAppropriateKeyringsAndExecDropsLocalRings() {
        val session = owner.joinSession(null)
        val thread = owner.getId(-1, true)
        val process = owner.getId(-2, true)
        val sibling = owner.fork(credentials, sameProcess = true)
        val child = owner.fork(credentials, sameProcess = false)
        assertEquals(process, sibling.getId(-2, false))
        failure(Errno.ENOKEY) { sibling.getId(-1, false) }
        failure(Errno.ENOKEY) { child.getId(-2, false) }
        assertEquals(session, child.getId(-3, false))
        child.joinSession(null)
        assertEquals(session, owner.getId(-3, false))
        owner.exec()
        failure(Errno.ENOKEY) { owner.getId(thread, false) }
        failure(Errno.ENOKEY) { owner.getId(-2, false) }
        assertEquals(process, sibling.getId(-2, false))
        assertEquals(session, owner.getId(-3, false))
        sibling.close()
        failure(Errno.ENOKEY) { owner.getId(process, false) }
    }

    @Test
    fun lazyProcessKeyringCreationIsSharedAfterThreadClone() {
        val sibling = owner.fork(credentials, sameProcess = true)
        val process = sibling.getId(-2, true)
        assertEquals(process, owner.getId(-2, false))
        assertNotEquals(owner.getId(-1, true), sibling.getId(-1, true))
        sibling.close()
        assertEquals(process, owner.getId(-2, false))
        owner.close()
        owner.close()
        assertTrue(owner.closed)
    }

    @Test
    fun joinsNamedSessionsAndKeepsUserKeyringsSeparate() {
        val session = owner.joinSession("login")
        assertEquals("keyring;1000;100;3f130000;login\u0000", owner.describe(session).decodeToString())
        owner.setPermissions(session, 0x3f1b0000)
        val sameUser = store.Context(credentials)
        assertEquals(session, sameUser.joinSession("login"))
        val user = owner.getId(-4, false)
        assertEquals("keyring;1000;65534;1f3f0000;_uid.1000\u0000", owner.describe(user).decodeToString())
        assertEquals(user, sameUser.getId(-4, false))
        val other = store.Context(Credentials(Credentials.Identity(1001, 1001, 1001), credentials.groupIds))
        assertNotEquals(user, other.getId(-4, false))
        assertNotEquals(session, other.joinSession("login"))
        assertEquals(user, owner.search(-5, KeyStore.Type.KEYRING, "_uid.1000", 0))
    }

    @Test
    fun defersSessionReplacementUntilParentReturnsToUserspace() {
        val original = owner.joinSession(null)
        val child = owner.fork(credentials, sameProcess = false)
        val replacement = child.joinSession(null)
        child.sessionToParent(owner)
        assertTrue(owner.hasPendingSession)
        assertEquals(original, owner.getId(-3, false))
        child.close()
        owner.applyPendingSession()
        assertEquals(replacement, owner.getId(-3, false))
        assertFalse(owner.hasPendingSession)
    }

    @Test
    fun persistentKeyringsSurviveContextExitAndRespectTheirExpiry() {
        owner.joinSession(null)
        val ring = owner.persistent(-1, -3)
        val key = owner.add(KeyStore.Type.USER, "token", payload, ring)
        owner.close()
        val later = store.Context(credentials)
        later.joinSession(null)
        assertEquals(ring, later.persistent(-1, -3))
        assertContentEquals(payload, later.read(key))
        failure(Errno.EPERM) { later.persistent(1001, -3) }
        later.close()
        now += store.persistentLifetime + 301
        store.collect()
        val fresh = store.Context(credentials)
        fresh.joinSession(null)
        assertNotEquals(ring, fresh.persistent(-1, -3))
        failure(Errno.ENOKEY) { fresh.read(key) }
    }

    @Test
    fun preservesRequestDefaultsAndReturnsEnokeyWithoutAnUpcallProvider() {
        assertEquals(0, owner.setRequestDefault(-1))
        assertEquals(0, owner.setRequestDefault(2))
        val child = owner.fork(credentials, sameProcess = false)
        child.exec()
        assertEquals(2, child.setRequestDefault(-1))
        failure(Errno.EINVAL) { owner.setRequestDefault(6) }
        failure(Errno.ENOKEY) { owner.request(KeyStore.Type.USER, "missing", 0) }
    }

    @Test
    fun preservesByteDescriptionsAndSerialAlignment() {
        val session = owner.joinSession(null)
        val key = owner.add(KeyStore.Type.USER, "\u00ff;token", payload, session)
        assertTrue(owner.describe(key).contains(-1))
        val bytes = owner.read(session, 4uL)
        var decoded = 0
        repeat(Int.SIZE_BYTES) { decoded = decoded or ((bytes[it].toInt() and 0xff) shl (it * 8)) }
        assertEquals(key, decoded)
        failure(Errno.EINVAL) { owner.read(session, 3uL) }
        failure(Errno.ENOKEY) { owner.read(0) }
        failure(Errno.ENOTDIR) { owner.clear(key) }
    }

    @Test
    fun releasesDeepGraphsWithoutRecursionOrResidualQuota() {
        val context = store.Context(Credentials())
        val session = context.joinSession(null)
        var destination = session
        repeat(1024) {
            destination = context.add(KeyStore.Type.KEYRING, "ring$it", byteArrayOf(), destination)
        }
        val key = context.add(KeyStore.Type.USER, "token", payload, destination)
        assertEquals(key, context.search(session, KeyStore.Type.USER, "token", 0))
        context.clear(session)
        failure(Errno.ENOKEY) { context.read(key) }
        store.rootQuota.keys = 2
        store.rootQuota.bytes = 20
        context.add(KeyStore.Type.USER, "token", payload, session)
    }

    @Test
    fun filesystemIdentityChangesTransferThreadKeyringAccountingWithoutFailing() {
        val ring = owner.getId(-1, true)
        store.userQuota.keys = 0
        credentials.setFilesystemUserId(1001, privileged = true)
        owner.updateIdentity()
        assertEquals("keyring;1001;100;3f010000;_tid\u0000", owner.describe(ring).decodeToString())
        owner.close()
        store.userQuota.keys = 1
        val later = store.Context(credentials)
        later.getId(-1, true)
    }

    @Test
    fun validatesRequestDestinationBeforeSearchingAndCreatesMoveDestinationsLazily() {
        val session = owner.joinSession(null)
        failure(Errno.EINVAL) { owner.request(KeyStore.Type.USER, "missing", -9) }
        val readonly = owner.add(KeyStore.Type.KEYRING, "readonly", byteArrayOf(), session)
        owner.setPermissions(readonly, 0x01010000)
        failure(Errno.EACCES) { owner.request(KeyStore.Type.USER, "missing", readonly) }
        val key = owner.add(KeyStore.Type.USER, "token", payload, session)
        failure(Errno.ENOKEY) { owner.move(key, -1, session, 0u) }
        owner.move(key, session, -1, 0u)
        assertEquals(key, owner.search(-1, KeyStore.Type.USER, "token", 0))
    }

    @Test
    fun attributeOperationsCreateSpecialRingsAndNoopChownNeedsNoKey() {
        owner.chown(0, -1, -1)
        owner.setPermissions(-1, 0x3f010000)
        assertTrue(owner.getId(-1, false) > 0)
        owner.timeout(-2, 100u)
        assertTrue(owner.getId(-2, false) > 0)
        val other = store.Context(credentials)
        other.chown(-1, 1000, -1)
        assertTrue(other.getId(-1, false) > 0)
    }

    private fun failure(errno: Int, operation: () -> Unit) {
        assertEquals(errno, assertFailsWith<KeyFailure> { operation() }.errno)
    }
}
