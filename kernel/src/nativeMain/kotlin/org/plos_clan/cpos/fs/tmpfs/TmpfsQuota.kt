package org.plos_clan.cpos.fs.tmpfs

/** Resource accounting serialized by the owning filesystem's lock. */
internal class TmpfsQuota(private val limit: ULong?) {
    var used: ULong = 0uL
        private set

    val available: ULong
        get() = (limit ?: ULong.MAX_VALUE) - used

    fun reserve(amount: ULong): Boolean {
        if (amount > available) return false
        used += amount
        return true
    }

    fun release(amount: ULong) {
        check(amount <= used)
        used -= amount
    }
}
