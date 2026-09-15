<div align="center">
<img height="200px" src="https://github.com/user-attachments/assets/d72d810d-f5c1-4129-9781-1b91f9030711" />

<h1 align="center">CoolPotOS Kotlin</h1>
<h3>A simple operating system written in Kotlin/Native..</h3>
</div>

---

## Feature

- Symmetric multiprocessing
- Buddy allocator & 4-level page table
- TSC-deadline apic timer
- USB subsystem (XHCI, HID, RNDIS Ethernet)
- RRS-handoff scheduler
- VDSO
- ACPI AML
- network & unix socket
- devtmpfs & procfs & sysfs & erofs & fuse3
- Signal Delivery and Handling
- Unified cgroup v2 hierarchy, threaded groups, freezer and pids controller
- Linux programs binary compatible (glibc)
- GC & Coroutine & runtime exception

## Build

This project uses Gradle for kernel build, ISO packaging, and QEMU run.

- Supports kernel build, ISO packaging, and QEMU run
- Uses the official Limine 12.x prebuilt release and transparent loading
  of a maximum-compression gzip kernel
- The kernel targets x86_64; portable Kotlin code and tests also compile for the JVM

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
- `./gradlew prepareUserland`: Build the CachyOS EROFS root filesystem
- `./gradlew buildIso`: Build the UEFI ISO image
- `./gradlew run`: Run the ISO image in QEMU
- `./gradlew jvmTest`: Run portable Kotlin unit tests on the host
- `./gradlew qemuTest`: Run portable and native tests inside the kernel
- `./gradlew benchmark`: Run portable benchmarks on the JVM
- `./gradlew qemuBenchmark`: Boot the production rootfs and run system benchmarks
- `./gradlew clean`: Clean kernel build outputs
- `./gradlew cleanAll`: Remove entire build directory
- `./gradlew buildMlibc`: Build bundled mlibc

**Quick start:**

Release mode is the default; the commands below specify it explicitly.

**Build and run in one command**

```shell
./gradlew run
```

**Or step by step**

```shell
./gradlew buildIso
./gradlew run
```

Use `./gradlew run -PdebugMode=true` to start QEMU paused with its GDB server enabled.

The default console is the framebuffer-backed `fb0` in QEMU's GTK window.
The `console` value is passed verbatim to Limine; QEMU's display frontend is an
independent setting. For example, a headless COM1 console can be started with:

```shell
./gradlew run -Pconsole=ttyS0,115200n8 -PqemuDisplay=none
CONSOLE=ttyS0,115200n8 QEMU_DISPLAY=none ./gradlew run
```

QEMU's serial port is always connected to the current terminal. Its stdio
multiplexer uses `Ctrl-A X` to exit and `Ctrl-A H` to show shortcut help.
The default QEMU machine also exposes an RNDIS USB Ethernet adapter backed by
QEMU user networking, which requires no host network setup. To attach the adapter
to an existing host bridge instead, specify its interface name:

```shell
./gradlew run -PqemuBridge=virbr0
QEMU_BRIDGE=virbr0 ./gradlew run
```

`-PqemuBridge` takes precedence over `QEMU_BRIDGE`. If neither has a nonblank
value, QEMU uses the default user network. Bridge mode requires the bridge to
exist and QEMU's bridge helper to be permitted to attach to it. Gradle does not
create or configure the bridge. The guest obtains its address from that network's
DHCP server, or can use a static address. On a host bridge with an IP address,
the host can access the guest's address directly without port forwarding.

You need to install:
- Kotlin/Native (`konanc`, `cinterop`)
- Clang (`clang`, `clang++`)
- LLD (`ld.lld`)
- Rootless Podman (for userland packaging)
- `xorriso` (for ISO creation)
- `qemu-system-x86_64` (for emulation)
- Git and Gradle (included with Kotlin/Native)

`prepareUserland` builds the CachyOS root filesystem and installs `assets/init`
as `/init`, which starts systemd inside the writable overlay root. Gradle tracks
`assets/init` and `assets/userland.sh`; changes to either rebuild the EROFS archive.
Run `./gradlew buildIso` to also update the bootable ISO before testing it.

**Overridable environment variables:**

The assignments below show the default values where applicable. Optional
variables are unset by default. Set an environment variable to override its
value for a build.

| Environment variable                              | Purpose                                |
|---------------------------------------------------|----------------------------------------|
| `DEBUG_MODE=false`                                | Build a debug kernel and wait for gdb. |
| `CONSOLE=fb0`                                     | Kernel console passed to Limine.       |
| `CROSS_CC=clang`                                  | C compiler executable.                 |
| `CROSS_CXX=clang++`                               | C++ compiler executable.               |
| `LINKER=ld.lld`                                   | Kernel linker executable.              |
| `OBJCOPY=llvm-objcopy`                            | Object-copy executable.                |
| `XORRISO=xorriso`                                 | ISO creation executable.               |
| `QEMU=qemu-system-x86_64`                         | QEMU executable.                       |
| `QEMU_DISPLAY=gtk`                                | QEMU display frontend.                 |
| `QEMU_BRIDGE`                                     | Existing host bridge interface (`-PqemuBridge`). Unset uses user networking. |
| `KONAN_TOOLROOT=~/.konan/dependencies/xxx`        | Kotlin/Native GNU toolchain root.      |
| `MLIBC_PREFIX=kernel/build/mlibc-x86_64/prefix`   | mlibc installation prefix.             |
| `USERLAND_IMAGE=docker.io/cachyos/cachyos:latest` | OCI image used to build the rootfs.    |
| `QEMU_CPU_SET=0-7`                                | Host CPU set passed to `taskset`.      |
| `QEMU_MEMORY=2g`                                  | Guest memory passed to QEMU.           |
| `ACPI_AML_TABLE_DIR`                              | Firmware tables used by AML tests.     |

`ACPI_AML_TABLE_DIR` enables the full firmware regression in `jvmTest` and
must contain `dsdt.dat` plus `ssdt1.dat` through `ssdt17.dat`.
The regression is reported as skipped when the directory is not configured.

## Shared code and verification

`commonMain` owns portable algorithms, binary codecs, buffers, time values,
coroutine dispatch, memory-region bookkeeping, and their platform contracts.
`nativeMain` supplies native memory access, IRQ critical sections, clocks,
page-cache backing, hardware integration, and kernel initialization.
`kernelMain` supplies the production boot workload. Common code is checked by
the JVM compiler without C interop, mlibc, or bootloader dependencies.

`commonTest` runs on the JVM and alongside `nativeTest` in the QEMU test
image. `qemuMain` supplies serial reporting and completion; `qemuTest` uses
the Kotlin/Native compiler's generated suites. `commonBenchmark` contains
JVM algorithm benchmarks for memory-region lookup, pathname parsing, and
the coroutine timer queue: eight parameter combinations in total.

QEMU tests need QEMU, xorriso, and a KVM host exposing
TSC-deadline support. The test image has no userspace rootfs. `qemuCpu`,
`qemuAcceleration`, `qemuSmp`, and `qemuMemory` configure this image;
changing acceleration does not remove the kernel's clock requirements.
`qemuTimeout` sets the timeout per boot in seconds (default 600), and
`qemuFilter` selects test case names using a regular expression.

```shell
./gradlew qemuTest -PqemuFilter='KernelDispatcherTest|NativeMemoryTest'
./gradlew benchmark
./gradlew qemuBenchmark
```

JVM benchmarks use three independent JMH forks. `benchmarkWarmups`,
`benchmarkIterations`, and `benchmarkIterationMillis` default to 5, 10,
and 1000. Warmup and measurement iterations each last one second by default.
The raw report retains all fork samples. Repetition reduces sampling and JIT
variation, but shared CI workers can still differ substantially between runs.

`qemuBenchmark` builds the production kernel and rootfs, including the usual
init, overlay filesystem, systemd services, and QEMU devices. It adds the
`cpos.benchmark` boot argument to activate `benchmark.service` after
`multi-user.target`. Normal boots do not run the service. Rootfs changes are
part of the measured system; kernel and rootfs hashes are retained in reports.
Podman is required to build the rootfs.

The default system measurements are:

- `boot.toBenchmarkService`: guest boot-clock nanoseconds when the benchmark
  service starts its Kotlin/Native workload, after `multi-user.target`. This includes
  service timeouts and runtime startup, but excludes firmware and the
  bootloader; the kernel clock starts during kernel initialization.
- `boot.usedMemory`: `(MemTotal - MemAvailable) * 1024` from `/proc/meminfo`,
  before command warmup. This uses the kernel's managed-memory accounting.
- `syscall.getpid`: average nanoseconds per userspace `getpid()` call through
  glibc and the real kernel syscall entry, including Kotlin/Native call overhead.
- `scheduler.threadWakeup`: average nanoseconds from signaling a condition
  variable to the waiting POSIX thread resuming and acquiring its mutex. A
  handshake places the receiver back in its wait before the next signal.
  This includes synchronization and scheduler overhead, not just context switching.
- `pipe.yesDd[bytes=67108864]`: elapsed nanoseconds for a userspace `yes | dd`
  pipeline transferring 64 MiB through 512-byte full-block reads into
  `/dev/null`. Progress output is disabled and both pipeline exit statuses
  are checked.

`benchmarkBoots` defaults to three independent boots. Each boot records its
startup and memory samples once, then runs each timed benchmark with five
warmup samples and ten retained samples. Syscall and thread samples each run
for one second; command samples run to completion. These settings are defined
in `kernel/src/qemuBenchmark/kotlin/KernelEntry.kt`, independently of the JVM
benchmark Gradle properties; only the benchmark boot switch is passed through
the kernel command line. Measurements execute sequentially alongside
normal system services. Add `Benchmark` implementations or `CommandBenchmark`
instances in `kernel/src/qemuBenchmark/kotlin` to extend the suite.

`qemuBenchmark` is a standard Kotlin/Native userspace executable compiled
independently of the kernel HAL and installed into the production rootfs.
It uses Kotlin time APIs, kotlinx-io, and the existing POSIX bindings. The
shared Kotlin report protocol lives in `src/qemuMain`; `src/qemuTest` runs
compiler-generated kernel tests. The Kotlin/JVM runner in `src/qemuHost` launches QEMU,
validates the serial protocol, and serializes reports with kotlinx-serialization.
There is no Python runner, custom C shim, or benchmark-specific kernel entry.

QEMU tests write `serial.log`, `results.json`, and `junit.xml` beneath
`kernel/build/qemuTest/results`. System benchmarks write per-boot serial logs
and `boot.json` reports, plus one combined `system.json`, beneath
`kernel/build/qemuBenchmark/results`. Timeouts, command failures, missing
measurements, and unexpected QEMU exits fail the task. Raw samples from all
boots are preserved; a failed boot stops the series. Host report validation can be run with
`./gradlew jvmTest`.

Normal `check` runs JVM tests. Normal `assemble` builds the production kernel
without linking verification images.

## Kernel coroutines

Launch structured kernel work through `KernelCoroutines.scope` or child scopes
derived from it. The kernel dispatcher supports `launch`, `async`, cancellation,
timeouts, and non-blocking `delay`; continuations execute on the BSP bootstrap
thread.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.plos_clan.cpos.coroutines.KernelCoroutines

KernelCoroutines.scope.launch {
    delay(10)
    println("Kernel coroutine resumed")
}
```

`Dispatchers.Default`, `Dispatchers.IO`, and `Dispatchers.Main` are not kernel
execution targets. Long compute loops must suspend or yield cooperatively so
other kernel work can run. AML SCI/GPE pending events run in a dedicated child
of the kernel scope; it processes bounded batches and suspends when idle rather
than polling in the bootstrap loop.

## Dependencies

* mlibc [managarm/mlibc](https://github.com/managarm/mlibc)
* libos-terminal [plos-clan/libos-terminal](https://github.com/plos-clan/libos-terminal)
* libzstd-decompress [facebook/zstd](https://github.com/facebook/zstd)

## License

This project is licensed under the [0BSD License](LICENSE).
