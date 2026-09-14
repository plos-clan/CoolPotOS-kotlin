package org.plos_clan.cpos.utils

import kotlin.uuid.Uuid

internal object BootIdentity {
    lateinit var id: Uuid
        private set

    fun initialize(id: Uuid) {
        check(!::id.isInitialized)
        this.id = id
    }
}
