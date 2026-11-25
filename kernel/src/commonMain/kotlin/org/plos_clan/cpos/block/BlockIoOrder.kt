package org.plos_clan.cpos.block

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.plos_clan.cpos.utils.CriticalSection

internal class BlockIoOrder(
    private val lock: CriticalSection,
    private val await: (Deferred<Unit>) -> Unit,
) {
    private class Request(val first: ULong, val last: ULong, val operation: BlockOperation) {
        var completion: CompletableDeferred<Unit>? = null

        fun dependency(next: Request): Deferred<Unit>? {
            if (last < next.first || next.last < first) return null
            if (operation == BlockOperation.READ && next.operation == BlockOperation.READ) return null
            return completion ?: CompletableDeferred<Unit>().also { completion = it }
        }
    }

    private val pending = mutableListOf<Request>()

    fun <T> run(first: ULong, last: ULong, operation: BlockOperation, transfer: () -> T): T {
        val request = Request(first, last, operation)
        val dependencies = lock.withLock {
            val previous = if (pending.isEmpty()) emptyList()
            else pending.mapNotNull { it.dependency(request) }
            pending.add(request)
            previous
        }
        return try {
            dependencies.forEach(await)
            transfer()
        } finally {
            val completion = lock.withLock {
                pending.remove(request)
                request.completion
            }
            completion?.complete(Unit)
        }
    }
}
