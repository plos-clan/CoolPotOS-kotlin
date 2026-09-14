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
- `./gradlew qemuBenchmark`: Run portable and native benchmarks inside the kernel
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

`commonTest` and `commonBenchmark` contain shared verification workloads.
`nativeTest` and `nativeBenchmark` contain kernel-specific workloads; they are
compiled into independent QEMU images, not executed as host programs.
`qemuMain` supplies serial reporting and completion, while `qemuTest` and
`qemuBenchmark` supply their own boot entries and runners. Tests use the
Kotlin/Native compiler's generated suites. Benchmarks use kotlinx-benchmark's
generated descriptors for parameterization, setup, teardown, and blackholes.

QEMU verification needs Python 3.11+, QEMU, xorriso, and a KVM host exposing
TSC-deadline support. It uses a headless UEFI image with no userspace rootfs and
does not run Podman. `qemuCpu`, `qemuAcceleration`, `qemuSmp`, and `qemuMemory`
configure the machine; changing acceleration does not remove the kernel's
clock and timer requirements. `qemuTimeout` sets the host timeout in seconds
(default 600), and `qemuFilter` selects case names using a regular expression.

```shell
./gradlew qemuTest -PqemuFilter='KernelDispatcherTest|NativeMemoryTest'
./gradlew qemuBenchmark -PbenchmarkWarmups=1 -PbenchmarkIterations=2 -PbenchmarkIterationMillis=10
```

Both benchmark runners share `benchmarkWarmups`, `benchmarkIterations`, and
`benchmarkIterationMillis`, defaulting to 5, 10, and 500. Short runs validate
the workloads; performance comparisons need longer sampling on the same
machine, acceleration, compiler, and GC settings. QEMU timing excludes boot,
setup, teardown, and serial output and records each sample's operation count
and elapsed nanoseconds.

QEMU writes `serial.log`, `results.json`, and `junit.xml` beneath
`kernel/build/qemuTest/results` or `kernel/build/qemuBenchmark/results`.
Reports record the kernel hash and execution configuration. Missing cases,
incomplete samples, failures, unexpected exits, and timeouts fail the task.
Normal `check` runs JVM tests; QEMU runs are explicit. Normal `assemble` builds
the production kernel without linking verification images.

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
