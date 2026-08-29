package org.plos_clan.cpos.tasks

class Credentials(
    userIds: Identity = Identity.ROOT,
    groupIds: Identity = Identity.ROOT,
    supplementaryGroups: List<Int> = emptyList(),
) {
    data class Identity(
        val real: Int,
        val effective: Int,
        val saved: Int,
        val filesystem: Int = effective,
    ) {
        init {
            require(real >= 0 && effective >= 0 && saved >= 0 && filesystem >= 0)
        }

        fun containsIdentity(id: Int): Boolean = id == real || id == effective || id == saved

        fun contains(id: Int): Boolean = containsIdentity(id) || id == filesystem

        companion object {
            val ROOT = Identity(0, 0, 0)
        }
    }

    data class UserIdChange(val previous: Identity, val current: Identity)

    var userIds = userIds
        private set
    var groupIds = groupIds
        private set
    var supplementaryGroups = supplementaryGroups.toList()
        private set

    fun inherit(parent: Credentials) {
        userIds = parent.userIds
        groupIds = parent.groupIds
        supplementaryGroups = parent.supplementaryGroups
    }

    fun setUserId(id: Int, privileged: Boolean): UserIdChange? {
        val previous = userIds
        val current = if (privileged) {
            Identity(id, id, id)
        } else {
            if (id != previous.real && id != previous.saved) return null
            previous.copy(effective = id, filesystem = id)
        }
        userIds = current
        return UserIdChange(previous, current)
    }

    fun setGroupId(id: Int, privileged: Boolean): Boolean {
        val previous = groupIds
        groupIds = if (privileged) {
            Identity(id, id, id)
        } else {
            if (id != previous.real && id != previous.saved) return false
            previous.copy(effective = id, filesystem = id)
        }
        return true
    }

    fun setResUserIds(
        real: Int?,
        effective: Int?,
        saved: Int?,
        privileged: Boolean,
    ): UserIdChange? {
        val previous = userIds
        if (!privileged && sequenceOf(real, effective, saved)
                .filterNotNull()
                .any { !previous.containsIdentity(it) }
        ) {
            return null
        }
        val current = Identity(
            real = real ?: previous.real,
            effective = effective ?: previous.effective,
            saved = saved ?: previous.saved,
            filesystem = effective ?: previous.effective,
        )
        userIds = current
        return UserIdChange(previous, current)
    }

    fun setResGroupIds(
        real: Int?,
        effective: Int?,
        saved: Int?,
        privileged: Boolean,
    ): Boolean {
        val previous = groupIds
        if (!privileged && sequenceOf(real, effective, saved)
                .filterNotNull()
                .any { !previous.containsIdentity(it) }
        ) {
            return false
        }
        groupIds = Identity(
            real = real ?: previous.real,
            effective = effective ?: previous.effective,
            saved = saved ?: previous.saved,
            filesystem = effective ?: previous.effective,
        )
        return true
    }

    fun setReUserIds(real: Int?, effective: Int?, privileged: Boolean): UserIdChange? {
        val previous = userIds
        if (!privileged &&
            (real != null && real != previous.real && real != previous.effective ||
                    effective != null && !previous.containsIdentity(effective))
        ) {
            return null
        }
        val newReal = real ?: previous.real
        val newEffective = effective ?: previous.effective
        val newSaved = if (real != null ||
            effective != null && effective != previous.real
        ) {
            newEffective
        } else {
            previous.saved
        }
        val current = Identity(newReal, newEffective, newSaved, newEffective)
        userIds = current
        return UserIdChange(previous, current)
    }

    fun setReGroupIds(real: Int?, effective: Int?, privileged: Boolean): Boolean {
        val previous = groupIds
        if (!privileged &&
            (real != null && real != previous.real && real != previous.effective ||
                    effective != null && !previous.containsIdentity(effective))
        ) {
            return false
        }
        val newReal = real ?: previous.real
        val newEffective = effective ?: previous.effective
        val newSaved = if (real != null ||
            effective != null && effective != previous.real
        ) {
            newEffective
        } else {
            previous.saved
        }
        groupIds = Identity(newReal, newEffective, newSaved, newEffective)
        return true
    }

    fun setFilesystemUserId(id: Int?, privileged: Boolean): Int {
        val previous = userIds.filesystem
        if (id != null && (privileged || userIds.contains(id))) {
            userIds = userIds.copy(filesystem = id)
        }
        return previous
    }

    fun setFilesystemGroupId(id: Int?, privileged: Boolean): Int {
        val previous = groupIds.filesystem
        if (id != null && (privileged || groupIds.contains(id))) {
            groupIds = groupIds.copy(filesystem = id)
        }
        return previous
    }

    fun replaceSupplementaryGroups(groups: List<Int>) {
        require(groups.all { it >= 0 })
        supplementaryGroups = groups.toList()
    }
}
