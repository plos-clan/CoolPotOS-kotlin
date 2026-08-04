# Kernel Coroutines Design

## Goal

Integrate the official `kotlinx-coroutines-core` runtime into the CoolPotOS
Kotlin/Native kernel. The first release provides structured concurrency,
kernel-owned dispatch, cancellation, exception handling, timeouts, and
non-blocking delays. Converting device and filesystem I/O to suspending APIs is
outside this release.

## Constraints

- The kernel is a freestanding Kotlin/Native binary and cannot depend on an OS
  event loop.
- Native kernel threads are scheduled by the existing `fast_handoff` scheduler.
- Kotlin continuations must never run in interrupt context.
- The current scheduler has no direct park/wake primitive for arbitrary Kotlin
  work and no cross-CPU wake IPI dedicated to coroutine dispatch.
- HPET provides the monotonic nanosecond clock after ACPI initialization.
- The implementation must not modify or vendor `kotlinx.coroutines` sources.

## Dependency

Add `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` to `nativeMain`.
Gradle must resolve the Kotlin/Native variant, and the final kernel link must
remain free of unresolved host-OS symbols. APIs marked internal by the library,
including `Delay`, are isolated behind the kernel dispatcher implementation and
opted into only in that file.

If the dependency exposes a missing platform symbol during the final link, add
the smallest bridge compatible with the existing mlibc and kernel runtime. Do
not fork, patch, or replace the coroutine library.

## Architecture

### KernelDispatcher

`KernelDispatcher` extends `CoroutineDispatcher` and implements `Delay`. It
owns two independent collections protected by `IrqSpinLock`:

- an immediate FIFO of `Runnable` instances;
- a binary min-heap of delayed tasks ordered by monotonic deadline and then by
  an increasing sequence number.

`dispatch` only appends to the FIFO. It does not execute user code. This keeps
dispatch safe when called by another CPU and prevents continuation re-entry.

`scheduleResumeAfterDelay` and `invokeOnTimeout` convert milliseconds to a
saturating nanosecond deadline using HPET time. Cancellation marks the delayed
entry disposed. Heap removal is lazy: disposed entries are discarded while
examining or popping the heap. A delayed continuation is claimed exactly once
under the queue lock before it is moved to the immediate FIFO.

No `Runnable`, continuation, exception handler, or cancellation callback runs
while the queue lock is held.

### KernelCoroutineQueue

Queue and heap mechanics live in a package-internal component with an injected
monotonic clock. This separates deterministic scheduling policy from HPET and
kernel boot code, allowing deadline ordering, cancellation, and overflow to be
tested without booting QEMU.

The queue exposes operations to enqueue immediate work, schedule delayed work,
claim a bounded batch of ready work, and report whether immediate work remains.
It does not expose its mutable collections.

### KernelCoroutines

`KernelCoroutines` is the kernel lifecycle owner. It contains:

- one `KernelDispatcher`;
- one root `SupervisorJob`;
- one `CoroutineExceptionHandler` that logs uncaught child failures without
  terminating the event loop;
- one public kernel `CoroutineScope` composed from those elements.

Initialization is idempotent and occurs after process and scheduler setup and
after HPET is available. Initialization failure is logged and stops the boot
path from advertising coroutine availability.

The root job is intentionally process-lifetime state. A test-only or internal
shutdown operation cancels it and drains cancellation work, but normal kernel
boot never shuts it down.

### BSP Event Loop

The existing BSP bootstrap thread is the only coroutine executor in the first
release. `kernelMain` initializes `KernelCoroutines`, enables the scheduler and
interrupts, starts the init program, and then enters the coroutine event loop
instead of its current unconditional interrupt-wait loop.

Each event-loop iteration:

1. moves all due delayed entries to the immediate queue;
2. claims and runs at most 64 immediate tasks;
3. immediately repeats if ready work remains;
4. otherwise executes `wait_for_interrupt()`.

Timer interrupts wake the BSP regularly, so delayed work becomes runnable
without busy waiting. A dispatch from another CPU while the BSP is halted is
observed no later than the next scheduler timer interrupt. Adding a dedicated
cross-CPU wake IPI and per-CPU coroutine executors is future work.

The dispatcher provides cooperative concurrency. A coroutine that performs a
long computation without suspension can delay other coroutines on the BSP;
this matches normal coroutine semantics and is documented in the public API.

## Public API

Kernel code launches structured work through `KernelCoroutines.scope` or a
child scope derived from it. The official library supplies `launch`, `async`,
`await`, `withContext`, `coroutineScope`, `supervisorScope`, `withTimeout`, and
job cancellation. Kernel code uses `KernelCoroutines.dispatcher` whenever an
explicit dispatcher is required.

The global `Dispatchers.Default`, `Dispatchers.IO`, and `Dispatchers.Main` are
not kernel execution targets and are not used by CoolPotOS code. The integration
does not promise that host-oriented dispatchers work in a freestanding kernel.

## Time And Cancellation Semantics

- `delay(0)` is dispatched normally and does not enter the delayed heap.
- Positive delays use a monotonic HPET deadline.
- Deadline arithmetic saturates at `ULong.MAX_VALUE`.
- Equal deadlines resume in scheduling order.
- Disposing a timeout or cancelling a delayed continuation prevents execution
  when cancellation wins the queue-lock claim.
- If the event loop claims an entry first, normal coroutine cancellation checks
  in `CancellableContinuation` decide whether its body resumes.
- Exceptions from one root child are logged by the handler; the supervisor keeps
  sibling coroutines and the event loop alive.

## Boot Integration

The boot order becomes:

1. initialize memory, ACPI/HPET, SMP, processes, and the native scheduler;
2. initialize the kernel coroutine owner;
3. enable the native scheduler and interrupts;
4. start the init user program;
5. run the BSP coroutine event loop permanently.

Successful initialization prints one concise line containing the dispatcher
name and CPU affinity. Failure returns from kernel boot initialization with an
explicit diagnostic rather than entering an event loop that cannot satisfy
delay guarantees.

## Testing

Deterministic native unit tests cover:

- FIFO ordering for immediate work;
- deadline and sequence ordering for delayed work;
- zero delay;
- lazy disposal and cancellation races;
- exactly-once claiming;
- saturating deadline arithmetic;
- the 64-task batch boundary;
- supervisor isolation and exception reporting.

Build verification must resolve the Native dependency, compile the tests,
compile C and Kotlin sources, and link `kernel.elf`. A permanent smoke-test hook
is enabled only by the `coroutine-smoke` kernel command-line flag. It launches
one coroutine, suspends with `delay`, resumes once, and prints the stable serial
marker `Coroutine smoke test passed`. Normal boots do not run this validation.
A QEMU smoke run adds the flag and must observe both the initialization log and
the success marker.

## Acceptance Criteria

- The project uses the official `kotlinx-coroutines-core` Native artifact.
- Kernel code can use `launch`, `async/await`, structured child scopes,
  cancellation, `withTimeout`, and non-blocking `delay` on the kernel dispatcher.
- Continuations run only on the BSP event loop and never in interrupt context.
- Delayed and cancelled work follows the semantics defined above.
- The native test suite passes, `kernel.elf` links, and the QEMU smoke validation
  resumes a delayed coroutine exactly once and emits its stable success marker.
- No upstream branch is modified or pushed; work remains on the fork feature
  branch until reviewed.

## Deferred Work

- suspending device, terminal, filesystem, and syscall APIs;
- per-CPU coroutine executors and dispatcher affinity;
- a dedicated cross-CPU wake IPI;
- coroutine-aware task parking in `fast_handoff`;
- support guarantees for host-oriented `Dispatchers` implementations.
