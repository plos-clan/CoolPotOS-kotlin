package org.plos_clan.cpos.block

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking
import org.plos_clan.cpos.utils.CriticalSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockIoOrderTest {
    private class Section : CriticalSection() {
        val lock = ReentrantLock()
        override fun acquire(): ULong {
            lock.lock()
            return 0uL
        }
        override fun release(state: ULong) = lock.unlock()
    }

    @Test
    fun conflictsQueueInOrderWithoutBlockingOtherBlocks() {
        val section = Section()
        val waiting = LinkedBlockingQueue<Unit>()
        val order = BlockIoOrder(section) { completion ->
            check(!section.lock.isHeldByCurrentThread)
            waiting.add(Unit)
            runBlocking { completion.await() }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val log = mutableListOf<String>()
        try {
            val first = pool.submit {
                order.run(0u, 1u, BlockOperation.READ) {
                    check(!section.lock.isHeldByCurrentThread)
                    entered.countDown()
                    check(release.await(10, SECONDS))
                }
            }
            assertTrue(entered.await(10, SECONDS))
            order.run(1u, 2u, BlockOperation.READ) { check(!section.lock.isHeldByCurrentThread) }
            val writer = pool.submit { order.run(1u, 2u, BlockOperation.WRITE) { log.add("write") } }
            assertEquals(Unit, waiting.poll(10, SECONDS))
            val reader = pool.submit { order.run(2u, 3u, BlockOperation.READ) { log.add("read") } }
            assertEquals(Unit, waiting.poll(10, SECONDS))
            order.run(4u, 4u, BlockOperation.WRITE) { check(!section.lock.isHeldByCurrentThread) }
            val flush = pool.submit { order.run(0u, ULong.MAX_VALUE, BlockOperation.WRITE) { log.add("flush") } }
            assertEquals(Unit, waiting.poll(10, SECONDS))
            assertFalse(writer.isDone || reader.isDone || flush.isDone)
            release.countDown()
            for (work in listOf(first, writer, reader, flush)) work.get(10, SECONDS)
            assertEquals(listOf("write", "read", "flush"), log)
            assertFailsWith<IllegalStateException> { order.run(1u, 1u, BlockOperation.WRITE) { error("I/O error") } }
            order.run(1u, 1u, BlockOperation.READ) { check(!section.lock.isHeldByCurrentThread) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
