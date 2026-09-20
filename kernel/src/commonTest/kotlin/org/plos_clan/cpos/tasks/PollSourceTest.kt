package org.plos_clan.cpos.tasks

import kotlin.test.*

class PollSourceTest {
    private class Observer(exclusive: Boolean = false) : PollSubscription(exclusive) {
        var notifications = 0
        var accepting = true
        var closes = 0
        override fun onClosed() { closes++ }
        override fun changed(): Boolean {
            notifications++
            return accepting
        }
    }

    @Test
    fun subscriptionsAreUniqueAndCancelledIndependently() {
        val first = PollSource()
        val second = PollSource()
        val observer = Observer()
        repeat(3) { observer.watch(first) }
        observer.watch(second)
        first.signal()
        second.signal()
        assertEquals(2, observer.notifications)
        observer.close()
        observer.close()
        assertEquals(1, observer.closes)
        first.signal()
        assertEquals(2, observer.notifications)
    }

    @Test
    fun exclusiveNotificationsDoNotSuppressOrdinaryListeners() {
        val source = PollSource()
        val first = Observer(true)
        val second = Observer(true)
        val ordinary = Observer()
        listOf(first, second, ordinary).forEach { it.watch(source) }
        source.signal()
        assertEquals(listOf(1, 0, 1), listOf(first, second, ordinary).map { it.notifications })
        first.accepting = false
        source.signal()
        assertEquals(listOf(2, 1, 2), listOf(first, second, ordinary).map { it.notifications })
        listOf(first, second, ordinary).forEach { it.close() }
    }
}
