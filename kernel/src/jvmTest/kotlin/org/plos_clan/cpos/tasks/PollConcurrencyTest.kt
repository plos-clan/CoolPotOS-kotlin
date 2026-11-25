package org.plos_clan.cpos.tasks

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlin.test.assertTrue

class PollConcurrencyTest {
    @Test
    fun closeRacingWithRegistrationLeavesNoSourceRetainingTheSubscription() {
        val pool = Executors.newFixedThreadPool(2)
        val sources = List(16) { PollSource() }
        val start = CyclicBarrier(3)
        try {
            repeat(128) {
                val subscription = object : PollSubscription() {
                    override fun changed(): Boolean = error("Closed subscription was notified")
                }
                val workers = List(2) { index ->
                    pool.submit {
                        start.await(5, SECONDS)
                        val order = if (index == 0) sources else sources.asReversed()
                        order.forEach(subscription::watch)
                    }
                }
                start.await(5, SECONDS)
                subscription.close()
                workers.forEach { it.get(5, SECONDS) }
                for (source in sources) {
                    source.signal()
                    assertTrue(source.attach(subscription))
                    source.detach(subscription)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
