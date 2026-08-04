# Kernel Coroutines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the official Kotlin/Native coroutine runtime with a BSP-owned kernel dispatcher, structured concurrency, cancellation, timeouts, and HPET-backed non-blocking delays.

**Architecture:** A pure Kotlin queue owns immediate FIFO and delayed min-heap policy. `KernelDispatcher` serializes access with an IRQ-safe critical section and implements `CoroutineDispatcher` plus `Delay`; `KernelCoroutines` owns the supervisor scope and runs work from the BSP bootstrap thread. Tests inject a clock and no-op critical section so scheduling semantics run on the host without privileged instructions.

**Tech Stack:** Kotlin 2.4.0 Multiplatform/Native, kotlinx-coroutines-core 1.10.2, kotlin.test, HPET, existing `IrqSpinLock`, Gradle, QEMU.

---

## File Map

- Modify `gradle/libs.versions.toml`: pin and expose the coroutine dependency.
- Modify `build.gradle.kts`: configure `nativeMain`/`nativeTest`, add the dependency, and support smoke-test kernel command lines.
- Create `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutineQueue.kt`: pure FIFO, delayed heap, cancellation state, and saturating deadlines.
- Create `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelDispatcher.kt`: IRQ-safe dispatcher and `Delay` adapter.
- Create `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutines.kt`: root scope, exception policy, BSP event loop, and smoke hook.
- Modify `kernel/kotlin/Kernel.kt`: initialize the runtime and replace the BSP halt loop with the coroutine event loop.
- Create `kernel/test/org/plos_clan/cpos/coroutines/CoroutineDependencyTest.kt`: dependency resolution smoke test.
- Create `kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineQueueTest.kt`: deterministic queue policy tests.
- Create `kernel/test/org/plos_clan/cpos/coroutines/KernelDispatcherTest.kt`: dispatch, delay, timeout, and cancellation tests.
- Create `kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineRuntimeTest.kt`: supervisor and exception-isolation tests.
- Modify `README.md`: document the kernel scope, supported coroutine APIs, and dispatcher limitation.

### Task 1: Resolve The Official Native Coroutine Runtime

**Files:**
- Modify: `build.gradle.kts:343`
- Modify: `gradle/libs.versions.toml`
- Create: `kernel/test/org/plos_clan/cpos/coroutines/CoroutineDependencyTest.kt`

- [ ] **Step 1: Configure the Native test source set and write the dependency smoke test**

Add the test source set without adding coroutines to `nativeMain` yet:

```kotlin
sourceSets.named("nativeMain") {
    kotlin.srcDir(kernelKotlinDir)
}

sourceSets.named("nativeTest") {
    kotlin.srcDir("kernel/test")
    dependencies {
        implementation(kotlin("test"))
    }
}
```

Create the test:

```kotlin
package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertTrue

class CoroutineDependencyTest {
    @Test
    fun supervisorJobIsAvailableToNativeCode() {
        val job = SupervisorJob()
        assertTrue(job.isActive)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run the test to verify the coroutine dependency is missing**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.CoroutineDependencyTest"`

Expected: compilation fails with `Unresolved reference 'kotlinx'` or `Unresolved reference 'SupervisorJob'`.

- [ ] **Step 3: Pin the library and add it to `nativeMain`**

Extend `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.0"
kotlinx-coroutines = "1.10.2"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

Change the `nativeMain` source-set block:

```kotlin
sourceSets.named("nativeMain") {
    kotlin.srcDir(kernelKotlinDir)
    dependencies {
        implementation(libs.kotlinx.coroutines.core)
    }
}
```

- [ ] **Step 4: Run the dependency test and inspect variant resolution**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.CoroutineDependencyTest"`

Expected: `CoroutineDependencyTest` passes.

Run: `./gradlew dependencyInsight --dependency kotlinx-coroutines-core --configuration nativeCompileKlibraries`

Expected: Gradle selects `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` for the Kotlin/Native compilation.

- [ ] **Step 5: Commit the dependency and harness**

```bash
git add gradle/libs.versions.toml build.gradle.kts kernel/test/org/plos_clan/cpos/coroutines/CoroutineDependencyTest.kt
git commit -m "build: add native coroutine runtime"
```

### Task 2: Build The Deterministic Coroutine Queue

**Files:**
- Create: `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutineQueue.kt`
- Create: `kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineQueueTest.kt`

- [ ] **Step 1: Write queue policy tests**

```kotlin
package org.plos_clan.cpos.coroutines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelCoroutineQueueTest {
    @Test
    fun immediateWorkIsFifoAndBatchBounded() {
        val queue = KernelCoroutineQueue()
        val events = mutableListOf<Int>()
        repeat(65) { value -> queue.enqueue(Runnable { events += value }) }

        queue.claimReady(nowNanos = 0uL, limit = 64).forEach(Runnable::run)
        assertEquals((0 until 64).toList(), events)
        assertTrue(queue.hasImmediateWork())

        queue.claimReady(nowNanos = 0uL, limit = 64).forEach(Runnable::run)
        assertEquals((0 until 65).toList(), events)
        assertFalse(queue.hasImmediateWork())
    }

    @Test
    fun delayedWorkUsesDeadlineThenInsertionOrder() {
        val queue = KernelCoroutineQueue()
        val events = mutableListOf<String>()
        queue.schedule(10uL, 2, Runnable { events += "late" })
        queue.schedule(10uL, 1, Runnable { events += "first" })
        queue.schedule(10uL, 1, Runnable { events += "second" })

        assertTrue(queue.claimReady(1_000_009uL, 8).isEmpty())
        queue.claimReady(1_000_010uL, 8).forEach(Runnable::run)
        assertEquals(listOf("first", "second"), events)

        queue.claimReady(2_000_010uL, 8).forEach(Runnable::run)
        assertEquals(listOf("first", "second", "late"), events)
    }

    @Test
    fun disposedDelayedWorkIsNeverClaimed() {
        val queue = KernelCoroutineQueue()
        var ran = false
        val task = queue.schedule(0uL, 1, Runnable { ran = true })

        assertTrue(queue.dispose(task))
        assertFalse(queue.dispose(task))
        queue.claimReady(1_000_000uL, 8).forEach(Runnable::run)
        assertFalse(ran)
    }

    @Test
    fun deadlineArithmeticSaturates() {
        val queue = KernelCoroutineQueue()
        val task = queue.schedule(ULong.MAX_VALUE - 5uL, Long.MAX_VALUE, Runnable {})
        assertEquals(ULong.MAX_VALUE, task.deadlineNanos)
    }

    @Test
    fun nonPositiveTimeoutIsImmediatelyReady() {
        val queue = KernelCoroutineQueue()
        var ran = false
        queue.schedule(20uL, 0, Runnable { ran = true })

        queue.claimReady(20uL, 1).single().run()
        assertTrue(ran)
    }
}
```

- [ ] **Step 2: Run the queue tests to verify they fail**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.KernelCoroutineQueueTest"`

Expected: compilation fails because `KernelCoroutineQueue` does not exist.

- [ ] **Step 3: Implement FIFO, delayed heap, disposal, and saturated deadlines**

```kotlin
package org.plos_clan.cpos.coroutines

internal enum class DelayedTaskState {
    PENDING,
    CLAIMED,
    DISPOSED,
}

internal class DelayedCoroutineTask(
    val deadlineNanos: ULong,
    val sequence: ULong,
    val runnable: Runnable,
) {
    var state: DelayedTaskState = DelayedTaskState.PENDING
}

internal class KernelCoroutineQueue {
    private val immediate = ArrayDeque<Runnable>()
    private val delayed = mutableListOf<DelayedCoroutineTask>()
    private var nextSequence = 0uL

    fun enqueue(runnable: Runnable) {
        immediate.addLast(runnable)
    }

    fun schedule(
        nowNanos: ULong,
        delayMillis: Long,
        runnable: Runnable,
    ): DelayedCoroutineTask {
        val task = DelayedCoroutineTask(
            deadlineNanos = saturatedDeadline(nowNanos, delayMillis),
            sequence = nextSequence++,
            runnable = runnable,
        )
        push(task)
        return task
    }

    fun dispose(task: DelayedCoroutineTask): Boolean {
        if (task.state != DelayedTaskState.PENDING) return false
        task.state = DelayedTaskState.DISPOSED
        return true
    }

    fun claimReady(nowNanos: ULong, limit: Int): List<Runnable> {
        require(limit > 0) { "limit must be positive" }
        promoteDue(nowNanos)
        val count = minOf(limit, immediate.size)
        return List(count) { immediate.removeFirst() }
    }

    fun hasImmediateWork(): Boolean = immediate.isNotEmpty()

    private fun promoteDue(nowNanos: ULong) {
        while (delayed.isNotEmpty()) {
            val next = delayed.first()
            if (next.state == DelayedTaskState.DISPOSED) {
                pop()
                continue
            }
            if (next.deadlineNanos > nowNanos) return
            pop()
            next.state = DelayedTaskState.CLAIMED
            immediate.addLast(next.runnable)
        }
    }

    private fun push(task: DelayedCoroutineTask) {
        delayed += task
        var index = delayed.lastIndex
        while (index > 0) {
            val parent = (index - 1) / 2
            if (!comesBefore(delayed[index], delayed[parent])) break
            val current = delayed[index]
            delayed[index] = delayed[parent]
            delayed[parent] = current
            index = parent
        }
    }

    private fun pop(): DelayedCoroutineTask {
        val result = delayed.first()
        val tail = delayed.removeAt(delayed.lastIndex)
        if (delayed.isEmpty()) return result
        delayed[0] = tail
        var index = 0
        while (true) {
            val left = index * 2 + 1
            if (left >= delayed.size) break
            val right = left + 1
            val child = if (right < delayed.size && comesBefore(delayed[right], delayed[left])) right else left
            if (!comesBefore(delayed[child], delayed[index])) break
            val current = delayed[index]
            delayed[index] = delayed[child]
            delayed[child] = current
            index = child
        }
        return result
    }

    private fun comesBefore(left: DelayedCoroutineTask, right: DelayedCoroutineTask): Boolean =
        left.deadlineNanos < right.deadlineNanos ||
            (left.deadlineNanos == right.deadlineNanos && left.sequence < right.sequence)

    private fun saturatedDeadline(nowNanos: ULong, delayMillis: Long): ULong {
        if (delayMillis <= 0L) return nowNanos
        val millis = delayMillis.toULong()
        val delayNanos = if (millis > ULong.MAX_VALUE / NANOS_PER_MILLISECOND) {
            ULong.MAX_VALUE
        } else {
            millis * NANOS_PER_MILLISECOND
        }
        return if (delayNanos > ULong.MAX_VALUE - nowNanos) ULong.MAX_VALUE else nowNanos + delayNanos
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000uL
    }
}
```

- [ ] **Step 4: Run the queue tests**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.KernelCoroutineQueueTest"`

Expected: all five queue tests pass.

- [ ] **Step 5: Commit the queue**

```bash
git add kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutineQueue.kt kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineQueueTest.kt
git commit -m "feat(coroutines): add deterministic work queue"
```

### Task 3: Implement Dispatch, Delay, Timeout, And Cancellation

**Files:**
- Create: `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelDispatcher.kt`
- Create: `kernel/test/org/plos_clan/cpos/coroutines/KernelDispatcherTest.kt`

- [ ] **Step 1: Write dispatcher behavior tests**

```kotlin
package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KernelDispatcherTest {
    private class FakeClock(var now: ULong = 0uL)

    private object NoOpCriticalSection : CriticalSection {
        override fun <T> withLock(block: () -> T): T = block()
    }

    @Test
    fun dispatchRunsOnlyWhenTheEventLoopPumps() {
        val dispatcher = dispatcher(FakeClock())
        var ran = false
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { ran = true })

        assertFalse(ran)
        assertEquals(1, dispatcher.runReadyBatch())
        assertTrue(ran)
    }

    @Test
    fun delayResumesAfterTheMonotonicDeadline() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val events = mutableListOf<String>()
        scope.launch {
            events += "start"
            delay(5)
            events += "end"
        }

        dispatcher.runReadyBatch()
        assertEquals(listOf("start"), events)
        clock.now = 4_999_999uL
        dispatcher.runReadyBatch()
        assertEquals(listOf("start"), events)
        clock.now = 5_000_000uL
        dispatcher.runReadyBatch()
        assertEquals(listOf("start", "end"), events)
        scope.cancel()
    }

    @Test
    fun cancellingDelayPreventsResume() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var resumed = false
        val job = scope.launch {
            delay(5)
            resumed = true
        }

        dispatcher.runReadyBatch()
        job.cancel()
        clock.now = 5_000_000uL
        while (dispatcher.hasReadyWork()) dispatcher.runReadyBatch()
        dispatcher.runReadyBatch()
        assertFalse(resumed)
        scope.cancel()
    }

    @Test
    fun runnableFailureIsReportedAndBatchContinues() {
        val failures = mutableListOf<Throwable>()
        val dispatcher = dispatcher(FakeClock(), failures::add)
        var secondRan = false
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { error("first") })
        dispatcher.dispatch(EmptyCoroutineContext, Runnable { secondRan = true })

        dispatcher.runReadyBatch()
        assertEquals("first", failures.single().message)
        assertTrue(secondRan)
    }

    @Test
    fun asyncAwaitAndTimeoutUseTheKernelDispatcher() {
        val clock = FakeClock()
        val dispatcher = dispatcher(clock)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var awaited = 0
        var timedOut = false
        scope.launch {
            awaited = async { 42 }.await()
            timedOut = withTimeoutOrNull(5) {
                delay(20)
                true
            } == null
        }

        while (dispatcher.hasReadyWork()) dispatcher.runReadyBatch()
        assertEquals(42, awaited)
        assertFalse(timedOut)

        clock.now = 5_000_000uL
        do {
            dispatcher.runReadyBatch()
        } while (dispatcher.hasReadyWork())
        assertTrue(timedOut)
        scope.cancel()
    }

    private fun dispatcher(
        clock: FakeClock,
        reporter: (Throwable) -> Unit = { throw it },
    ): KernelDispatcher = KernelDispatcher(
        nanoTime = { clock.now },
        criticalSection = NoOpCriticalSection,
        reportFailure = reporter,
    )
}
```

- [ ] **Step 2: Run the dispatcher tests to verify they fail**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.KernelDispatcherTest"`

Expected: compilation fails because `KernelDispatcher` and `CriticalSection` do not exist.

- [ ] **Step 3: Implement the IRQ-safe dispatcher**

```kotlin
@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.coroutines.CoroutineContext

internal interface CriticalSection {
    fun <T> withLock(block: () -> T): T
}

private class IrqCriticalSection(
    private val lock: IrqSpinLock = IrqSpinLock(),
) : CriticalSection {
    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
}

private class KernelDisposableHandle(
    private val disposeBlock: () -> Unit,
) : DisposableHandle {
    override fun dispose() = disposeBlock()
}

class KernelDispatcher internal constructor(
    private val nanoTime: () -> ULong = Hpet::nanoTime,
    private val criticalSection: CriticalSection = IrqCriticalSection(),
    private val reportFailure: (Throwable) -> Unit = { throwable -> throwable.printStackTrace() },
) : CoroutineDispatcher(), Delay {
    private val queue = KernelCoroutineQueue()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        criticalSection.withLock { queue.enqueue(block) }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val handle = schedule(
            timeMillis,
            Runnable {
                continuation.resume(Unit, onCancellation = { _, _, _ -> })
            },
        )
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = schedule(timeMillis, block)

    internal fun runReadyBatch(limit: Int = MAX_TASKS_PER_BATCH): Int {
        val work = criticalSection.withLock {
            queue.claimReady(nanoTime(), limit)
        }
        work.forEach { runnable ->
            try {
                runnable.run()
            } catch (throwable: Throwable) {
                reportFailure(throwable)
            }
        }
        return work.size
    }

    internal fun hasReadyWork(): Boolean =
        criticalSection.withLock(queue::hasImmediateWork)

    private fun schedule(timeMillis: Long, block: Runnable): DisposableHandle {
        val task = criticalSection.withLock {
            queue.schedule(nanoTime(), timeMillis, block)
        }
        return KernelDisposableHandle {
            criticalSection.withLock { queue.dispose(task) }
        }
    }

    override fun toString(): String = "KernelDispatcher[BSP]"

    internal companion object {
        const val MAX_TASKS_PER_BATCH = 64
    }
}
```

- [ ] **Step 4: Run dependency, queue, and dispatcher tests**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.*"`

Expected: all coroutine tests pass. If Kotlin reports an opt-in requirement on the three-argument cancellation callback, add `ExperimentalCoroutinesApi` to the file-level opt-in; do not change the callback semantics.

- [ ] **Step 5: Commit the dispatcher**

```bash
git add kernel/kotlin/org/plos_clan/cpos/coroutines/KernelDispatcher.kt kernel/test/org/plos_clan/cpos/coroutines/KernelDispatcherTest.kt
git commit -m "feat(coroutines): add kernel dispatcher and delay"
```

### Task 4: Own The Root Scope And BSP Event Loop

**Files:**
- Create: `kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutines.kt`
- Create: `kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineRuntimeTest.kt`

- [ ] **Step 1: Write supervisor isolation tests**

```kotlin
package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KernelCoroutineRuntimeTest {
    private object NoOpCriticalSection : CriticalSection {
        override fun <T> withLock(block: () -> T): T = block()
    }

    @Test
    fun failedChildDoesNotCancelSiblingOrRoot() {
        val failures = mutableListOf<Throwable>()
        val dispatcher = KernelDispatcher(
            nanoTime = { 0uL },
            criticalSection = NoOpCriticalSection,
            reportFailure = failures::add,
        )
        val runtime = createKernelCoroutineRuntime(dispatcher, failures::add)
        var siblingRan = false
        runtime.scope.launch { error("child failed") }
        runtime.scope.launch { siblingRan = true }

        while (dispatcher.hasReadyWork()) dispatcher.runReadyBatch()

        assertTrue(runtime.job.isActive)
        assertTrue(siblingRan)
        assertEquals("child failed", failures.single().message)
        runtime.job.cancel()
    }
}
```

- [ ] **Step 2: Run the runtime test to verify it fails**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.KernelCoroutineRuntimeTest"`

Expected: compilation fails because `createKernelCoroutineRuntime` does not exist.

- [ ] **Step 3: Implement lifecycle ownership and the event loop**

```kotlin
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.plos_clan.cpos.drivers.Hpet

internal data class KernelCoroutineRuntime(
    val job: CompletableJob,
    val scope: CoroutineScope,
)

internal fun createKernelCoroutineRuntime(
    dispatcher: KernelDispatcher,
    reportFailure: (Throwable) -> Unit,
): KernelCoroutineRuntime {
    val job = SupervisorJob()
    val handler = CoroutineExceptionHandler { _, throwable -> reportFailure(throwable) }
    return KernelCoroutineRuntime(
        job = job,
        scope = CoroutineScope(job + dispatcher + handler + CoroutineName("kernel")),
    )
}

object KernelCoroutines {
    private var initialized = false
    private lateinit var runtime: KernelCoroutineRuntime

    lateinit var dispatcher: KernelDispatcher
        private set

    val scope: CoroutineScope
        get() {
            check(initialized) { "Kernel coroutines are not initialized" }
            return runtime.scope
        }

    fun initialize(): Boolean {
        if (initialized) return true
        if (!Hpet.isReady) {
            println("Kernel coroutines: HPET is unavailable")
            return false
        }
        dispatcher = KernelDispatcher(reportFailure = ::reportFailure)
        runtime = createKernelCoroutineRuntime(dispatcher, ::reportFailure)
        initialized = true
        println("Kernel coroutines initialized dispatcher=$dispatcher")
        return true
    }

    fun launchSmokeTest() {
        scope.launch {
            delay(10)
            println("Coroutine smoke test passed")
        }
    }

    fun runEventLoop(): Nothing {
        check(initialized) { "Kernel coroutines are not initialized" }
        while (true) {
            dispatcher.runReadyBatch()
            if (dispatcher.hasReadyWork()) continue
            bridge.wait_for_interrupt()
        }
    }

    internal fun shutdown() {
        if (!initialized) return
        runtime.job.cancel()
        while (dispatcher.hasReadyWork()) dispatcher.runReadyBatch()
        initialized = false
    }

    private fun reportFailure(throwable: Throwable) {
        println("Uncaught kernel coroutine failure: $throwable")
        throwable.printStackTrace()
    }
}
```

- [ ] **Step 4: Run all coroutine tests**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.*"`

Expected: dependency, queue, dispatcher, and runtime tests all pass.

- [ ] **Step 5: Commit the lifecycle owner**

```bash
git add kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutines.kt kernel/test/org/plos_clan/cpos/coroutines/KernelCoroutineRuntimeTest.kt
git commit -m "feat(coroutines): add kernel scope and event loop"
```

### Task 5: Connect Coroutines To Kernel Boot

**Files:**
- Modify: `kernel/kotlin/Kernel.kt:1-65`

- [ ] **Step 1: Add a source-level boot integration assertion**

Create `kernel/test/org/plos_clan/cpos/coroutines/KernelBootContractTest.kt`:

```kotlin
package org.plos_clan.cpos.coroutines

import kotlin.test.Test
import kotlin.test.assertEquals

class KernelBootContractTest {
    @Test
    fun smokeMarkerIsStable() {
        assertEquals("Coroutine smoke test passed", COROUTINE_SMOKE_SUCCESS_MARKER)
    }
}
```

- [ ] **Step 2: Run the boot contract test before wiring boot**

Run: `./gradlew nativeTest --tests "org.plos_clan.cpos.coroutines.KernelBootContractTest"`

Expected: compilation fails because `COROUTINE_SMOKE_SUCCESS_MARKER` does not exist.

- [ ] **Step 3: Define the stable marker and initialize the event loop from `kernelMain`**

Move the marker in `KernelCoroutines.kt` to an internal constant and print it:

```kotlin
internal const val COROUTINE_SMOKE_SUCCESS_MARKER = "Coroutine smoke test passed"

fun launchSmokeTest() {
    scope.launch {
        delay(10)
        println(COROUTINE_SMOKE_SUCCESS_MARKER)
    }
}
```

Add the imports:

```kotlin
import org.plos_clan.cpos.coroutines.KernelCoroutines
```

Replace the final boot sequence:

```kotlin
Initrd.initialize()
if (!KernelCoroutines.initialize()) {
    return
}
println("Kernel load done!")
Scheduler.enableScheduler()
bridge.enable_interrupt()
Init.setupInitProgram()
if (Cmdline.boolean("coroutine-smoke") == true) {
    KernelCoroutines.launchSmokeTest()
}
KernelCoroutines.runEventLoop()
```

Remove the old `while (true) bridge.wait_for_interrupt()` loop.

- [ ] **Step 4: Compile Kotlin and link the kernel**

Run: `./gradlew linkDebugStaticNative linkKernel`

Expected: both tasks succeed and `build/kernel.elf` exists. Any unresolved platform symbol from coroutines must be resolved with the smallest bridge compatible with existing mlibc; record the exact symbol and bridge in the commit body.

- [ ] **Step 5: Run all Native tests again**

Run: `./gradlew nativeTest`

Expected: all Native tests pass.

- [ ] **Step 6: Commit boot integration**

```bash
git add kernel/kotlin/Kernel.kt kernel/kotlin/org/plos_clan/cpos/coroutines/KernelCoroutines.kt kernel/test/org/plos_clan/cpos/coroutines/KernelBootContractTest.kt
git commit -m "feat(coroutines): run dispatcher from kernel boot"
```

### Task 6: Add A Reproducible QEMU Smoke Boot

**Files:**
- Modify: `build.gradle.kts:149-158`
- Modify: `build.gradle.kts:511-523`

- [ ] **Step 1: Verify the smoke property does not yet alter the ISO**

Run before the property exists:

```powershell
.\gradlew.bat buildIso -PcoroutineSmoke=true
if (Select-String -Path 'build/iso/limine/limine.conf' -Pattern 'cmdline:.*coroutine-smoke' -Quiet) {
    throw 'Smoke flag unexpectedly present before build integration'
}
```

Expected: the build succeeds and the assertion confirms that the staged command line still lacks `coroutine-smoke`.

- [ ] **Step 2: Parse the property and make it an input to ISO staging**

Add beside `debugMode`:

```kotlin
val coroutineSmoke = settingBoolean("coroutineSmoke", "COROUTINE_SMOKE", false)
```

Replace the Limine config copy inside `stageIso`:

```kotlin
inputs.property("coroutineSmoke", coroutineSmoke)

from(limineConfigFile) {
    into("limine")
    filter { line: String ->
        if (coroutineSmoke && line.trimStart().startsWith("cmdline:")) {
            "$line coroutine-smoke"
        } else {
            line
        }
    }
}
```

- [ ] **Step 3: Build the smoke ISO and inspect the staged command line**

Run: `./gradlew buildIso -PcoroutineSmoke=true`

Run: `Select-String -Path build/iso/limine/limine.conf -Pattern 'cmdline:.*coroutine-smoke'`

Expected: one matching `cmdline` line and a successful `build/CoolPotOS.iso` build.

- [ ] **Step 4: Boot QEMU and assert the stable serial marker**

Run this PowerShell from the repository root:

```powershell
$logPath = Join-Path $PWD 'build/coroutine-smoke.log'
Remove-Item -LiteralPath $logPath -ErrorAction SilentlyContinue
$arguments = @(
    '-m', '512m', '-M', 'q35', '-cpu', 'qemu64,+x2apic',
    '-no-reboot', '-smp', '4',
    '-drive', 'if=pflash,format=raw,readonly=on,file=assets/ovmf-code.fd',
    '-serial', "file:$logPath",
    (Resolve-Path 'build/CoolPotOS.iso').Path
)
$process = Start-Process 'qemu-system-x86_64' -ArgumentList $arguments -PassThru -WindowStyle Hidden
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 250
        $output = if (Test-Path -LiteralPath $logPath) { Get-Content -Raw -LiteralPath $logPath } else { '' }
    } while ($output -notmatch 'Coroutine smoke test passed' -and [DateTime]::UtcNow -lt $deadline -and -not $process.HasExited)
    if ($output -notmatch 'Kernel coroutines initialized dispatcher=KernelDispatcher\[BSP\]' -or
        $output -notmatch 'Coroutine smoke test passed') {
        throw "Coroutine smoke markers were not observed. Serial output:`n$output"
    }
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
}
```

Expected: the script exits successfully after observing initialization and exactly one smoke success marker. Confirm exact count with:

```powershell
(Select-String -Path build/coroutine-smoke.log -Pattern 'Coroutine smoke test passed' -AllMatches).Matches.Count
```

Expected output: `1`.

- [ ] **Step 5: Commit smoke boot support**

```bash
git add build.gradle.kts
git commit -m "test(coroutines): add qemu smoke boot flag"
```

### Task 7: Document Usage And Perform Final Verification

**Files:**
- Modify: `README.md`
- Update locally: `graphify-out/`

- [ ] **Step 1: Add user-facing coroutine usage documentation**

Append this section to `README.md`:

````markdown
## Kernel coroutines

Kernel code can launch structured work through `KernelCoroutines.scope`. The
scope uses `KernelDispatcher`, supports `launch`, `async`, cancellation,
timeouts, and non-blocking `delay`, and executes continuations on the BSP
bootstrap thread.

```kotlin
KernelCoroutines.scope.launch {
    delay(10)
    println("resumed on the kernel dispatcher")
}
```

Use the kernel scope or an explicitly derived child scope. Host-oriented
`Dispatchers.Default`, `Dispatchers.IO`, and `Dispatchers.Main` are not kernel
execution targets. Long computations must suspend or yield cooperatively so
they do not monopolize the BSP event loop.
````

- [ ] **Step 2: Run formatting and test verification**

Run: `./gradlew nativeTest`

Expected: all tests pass.

Run: `./gradlew build`

Expected: C compilation, Kotlin/Native static library generation, and `build/kernel.elf` linking succeed.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 3: Refresh the repository knowledge graph**

Run: `graphify update .`

Expected: Graphify completes its AST-only incremental update. Keep existing untracked graph artifacts out of source commits unless the repository explicitly begins tracking them.

- [ ] **Step 4: Re-run the smoke ISO after the clean build**

Run: `./gradlew buildIso -PcoroutineSmoke=true`

Repeat Task 6 Step 4 and Step 4's exact-count command.

Expected: initialization marker is present and `Coroutine smoke test passed` occurs exactly once.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git commit -m "docs: describe kernel coroutine usage"
```

- [ ] **Step 6: Review the final branch without touching user-owned files**

Run: `git status --short`

Expected: only the pre-existing untracked `.codegraph/`, `.codex/`, `AGENTS.md`, and `graphify-out/` paths remain.

Run: `git log --oneline origin/main..HEAD`

Expected: the design/plan commits, six focused implementation commits, and the documentation commit are listed; no unrelated files appear in `git diff --stat origin/main...HEAD`.
