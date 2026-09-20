package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.PollSubscription
import org.plos_clan.cpos.utils.PollEvents
import kotlin.test.*

class EpollReadinessTest {
    private class Fixture : AutoCloseable {
        val vfs = Vfs()
        val caller = VfsOperationContext.KERNEL
        val context: FileSystemContext
        val epoll = Epoll()
        val files = ArrayList<OpenFileDescription>()
        val events = ArrayList<EpollEvent>()

        init {
            val registered = vfs.register(Sysfs)
            assertIs<VfsResult.Ok<Unit>>(registered)
            val created = vfs.createContext(Sysfs.name)
            context = assertIs<VfsResult.Ok<FileSystemContext>>(created).value
        }

        fun event(flags: UInt = PollEvents.POLLIN.toUInt()): OpenFileDescription {
            val result = vfs.createEventFd(caller, context, 0u, false, true)
            val file = assertIs<VfsResult.Ok<OpenFileDescription>>(result).value
            files += file
            watch(files.size, file, flags, files.size.toULong())
            return file
        }

        fun pipe(): Pair<OpenFileDescription, OpenFileDescription> {
            val result = vfs.createPipe(caller, context)
            val opened = assertIs<VfsResult.Ok<Pair<OpenFileDescription, OpenFileDescription>>>(
                result,
            )
            val pair = opened.value
            files.add(pair.first)
            files.add(pair.second)
            return pair
        }

        fun watch(descriptor: Int, file: OpenFileDescription, flags: UInt, data: ULong,
            operation: EpollControlOperation = EpollControlOperation.ADD) {
            val event = EpollEvent(flags, data)
            val result = epoll.control(descriptor, file, operation, event)
            assertIs<VfsResult.Ok<Unit>>(result)
        }

        fun write(
            file: OpenFileDescription,
            bytes: ByteArray = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0),
        ) {
            val buffer = ByteArrayBuffer(bytes)
            val result = file.write(caller, buffer, 0, bytes.size)
            assertEquals(bytes.size, result.bytesTransferred)
        }

        fun read(file: OpenFileDescription, count: Int) {
            val bytes = ByteArray(count)
            val buffer = ByteArrayBuffer(bytes)
            val result = file.read(caller, buffer, 0, count)
            assertEquals(count, result.bytesTransferred)
        }

        fun collect(): List<EpollEvent> {
            epoll.collect(caller, 8, events)
            return events.toList()
        }

        override fun close() {
            epoll.release()
            files.forEach { it.release() }
            context.release()
        }
    }

    @Test
    fun notificationsWakeOnlyTheOwningEpollAndLevelReadinessPersists() {
        Fixture().use { fixture ->
            val file = fixture.event()
            assertEquals(emptyList(), fixture.collect())
            var notified = 0
            val observer = object : PollSubscription() {
                override fun changed(): Boolean {
                    notified++
                    return true
                }
            }
            fixture.epoll.subscribe(fixture.caller, file.inode, observer)
            try {
                fixture.write(file)
                assertTrue(notified > 0)
                val expected = EpollEvent(1u, 1uL)
                repeat(2) { assertEquals(listOf(expected), fixture.collect()) }
                fixture.read(file, 8)
                assertEquals(emptyList(), fixture.collect())
            } finally { observer.close() }
        }
    }

    @Test
    fun oneShotRequiresRearmingAndClosedDescriptionsAreRemoved() {
        Fixture().use { fixture ->
            val flags = PollEvents.POLLIN.toUInt() or EpollEvents.ONE_SHOT
            val file = fixture.event(flags)
            fixture.write(file)
            assertEquals(1, fixture.collect().size)
            assertEquals(emptyList(), fixture.collect())
            fixture.watch(1, file, flags, 7uL, EpollControlOperation.MODIFY)
            val expected = EpollEvent(1u, 7uL)
            assertEquals(listOf(expected), fixture.collect())
            file.release()
            fixture.files.clear()
            assertEquals(emptyList(), fixture.collect())
        }
    }

    @Test
    fun pipeWritesAndFinalWriterClosePublishReadiness() {
        Fixture().use { fixture ->
            val (reader, writer) = fixture.pipe()
            fixture.watch(1, reader, PollEvents.POLLIN.toUInt(), 1uL)
            assertEquals(emptyList(), fixture.collect())
            fixture.write(writer, byteArrayOf(1))
            val expected = EpollEvent(1u, 1uL)
            assertEquals(listOf(expected), fixture.collect())
            fixture.read(reader, 1)
            assertEquals(emptyList(), fixture.collect())
            fixture.files.remove(writer)
            writer.release()
            assertTrue(fixture.collect().single().events and PollEvents.POLLHUP.toUInt() != 0u)
        }
    }

    @Test
    fun partialPipeReadsDoNotGenerateInputEdges() {
        Fixture().use { fixture ->
            val (reader, writer) = fixture.pipe()
            val flags = PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED
            fixture.watch(1, reader, flags, 1uL)
            assertEquals(emptyList(), fixture.collect())
            fixture.write(writer, byteArrayOf(1, 2))
            assertEquals(1, fixture.collect().size)
            fixture.read(reader, 1)
            assertEquals(emptyList(), fixture.collect())
            fixture.write(writer, byteArrayOf(3))
            assertEquals(1, fixture.collect().size)
        }
    }

    @Test
    fun modifyingInterestsRemovesPreviousSubscriptions() {
        Fixture().use { fixture ->
            val file = fixture.event(PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED)
            assertEquals(emptyList(), fixture.collect())
            val flags = PollEvents.POLLOUT.toUInt() or EpollEvents.EDGE_TRIGGERED
            fixture.watch(1, file, flags, 2uL, EpollControlOperation.MODIFY)
            val expected = EpollEvent(PollEvents.POLLOUT.toUInt(), 2uL)
            assertEquals(listOf(expected), fixture.collect())
            fixture.write(file)
            assertEquals(emptyList(), fixture.collect())
        }
    }

    @Test
    fun consecutiveEventWritesPublishNewInputEdges() {
        Fixture().use { fixture ->
            val file = fixture.event(PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED)
            assertEquals(emptyList(), fixture.collect())
            repeat(2) {
                fixture.write(file)
                val expected = EpollEvent(1u, 1uL)
                assertEquals(listOf(expected), fixture.collect())
                assertEquals(emptyList(), fixture.collect())
            }
        }
    }

    @Test
    fun pipeHangupDoesNotRequireInputInterest() {
        Fixture().use { fixture ->
            val (reader, writer) = fixture.pipe()
            fixture.watch(1, reader, PollEvents.POLLOUT.toUInt(), 1uL)
            assertEquals(emptyList(), fixture.collect())
            fixture.files.remove(writer)
            writer.release()
            val expected = EpollEvent(PollEvents.POLLHUP.toUInt(), 1uL)
            assertEquals(listOf(expected), fixture.collect())
        }
    }

    @Test
    fun levelTriggeredBatchesVisitEachRegistrationOnce() {
        Fixture().use { fixture ->
            val files = List(3) { fixture.event() }
            assertEquals(emptyList(), fixture.collect())
            files.forEach { fixture.write(it) }
            assertEquals(listOf(1uL, 2uL, 3uL), fixture.collect().map { it.data })
            repeat(6) { index ->
                fixture.epoll.collect(fixture.caller, 1, fixture.events)
                assertEquals((index % 3 + 1).toULong(), fixture.events.single().data)
            }
        }
    }

    @Test
    fun nestedReadinessDoesNotGenerateRepeatedInputEdges() {
        Fixture().use { fixture ->
            val file = fixture.event(PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED)
            val created = fixture.vfs.createEpoll(fixture.caller, fixture.context)
            val nestedFile = assertIs<VfsResult.Ok<OpenFileDescription>>(created).value
            fixture.files.add(nestedFile)
            val nested = assertIs<Epoll>(nestedFile.backend)
            val event = EpollEvent(PollEvents.POLLIN.toUInt(), 1uL)
            val registered = nested.control(1, file, EpollControlOperation.ADD, event)
            assertIs<VfsResult.Ok<Unit>>(registered)
            val flags = PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED
            fixture.watch(2, nestedFile, flags, 2uL)
            assertEquals(emptyList(), fixture.collect())
            fixture.write(file)
            assertEquals(setOf(1uL, 2uL), fixture.collect().map { it.data }.toSet())
            repeat(2) { assertEquals(emptyList(), fixture.collect()) }
        }
    }

    @Test
    fun edgeTriggeredReadinessDoesNotRepeatWithoutAnEvent() {
        Fixture().use { fixture ->
            val file = fixture.event(PollEvents.POLLIN.toUInt() or EpollEvents.EDGE_TRIGGERED)
            fixture.write(file)
            assertEquals(1, fixture.collect().size)
            repeat(4) { assertEquals(emptyList(), fixture.collect()) }
            fixture.read(file, 8)
            assertEquals(emptyList(), fixture.collect())
            fixture.write(file)
            assertEquals(1, fixture.collect().size)
        }
    }
}
