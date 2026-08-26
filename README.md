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
- USB subsystem (XHCI, HID)
- RRS-handoff scheduler
- VDSO
- ACPI AML
- devtmpfs & procfs & erofs rootfs
- Signal Delivery and Handling
- Linux programs binary compatible (glibc)
- GC & Coroutine & runtime exception

## Build

This project uses Gradle for kernel build, ISO packaging, and QEMU run.

- Supports kernel build, ISO packaging, and QEMU run
- Uses the official Limine 12.x prebuilt release and transparent loading
  of a maximum-compression gzip kernel
- Cross-platform compatible (Linux/macOS/Windows)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
- `./gradlew prepareUserland`: Build the CachyOS EROFS root filesystem
- `./gradlew buildIso`: Build the UEFI ISO image
- `./gradlew run`: Run the ISO image in QEMU
- `./gradlew nativeTest`: Run host-side Kotlin/Native unit tests
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

You need to install:
- Kotlin/Native (`konanc`, `cinterop`)
- Clang (`clang`, `clang++`)
- LLD (`ld.lld`)
- Rootless Podman (for userland packaging)
- `xorriso` (for ISO creation)
- `qemu-system-x86_64` (for emulation)
- Git and Gradle (included with Kotlin/Native)

**Overridable environment variables:**

The assignments below show the default values where applicable. Optional
variables are unset by default. Set an environment variable to override its
value for a build.

| Environment variable                              | Purpose                                |
|---------------------------------------------------|----------------------------------------|
| `DEBUG_MODE=false`                                | Build a debug kernel and wait for gdb. |
| `CROSS_CC=clang`                                  | C compiler executable.                 |
| `CROSS_CXX=clang++`                               | C++ compiler executable.               |
| `LINKER=ld.lld`                                   | Kernel linker executable.              |
| `OBJCOPY=llvm-objcopy`                            | Object-copy executable.                |
| `XORRISO=xorriso`                                 | ISO creation executable.               |
| `QEMU=qemu-system-x86_64`                         | QEMU executable.                       |
| `KONAN_TOOLROOT=~/.konan/dependencies/xxx`        | Kotlin/Native GNU toolchain root.      |
| `MLIBC_PREFIX=kernel/build/mlibc-x86_64/prefix`   | mlibc installation prefix.             |
| `USERLAND_IMAGE=docker.io/cachyos/cachyos:latest` | OCI image used to build the rootfs.    |
| `QEMU_CPU_SET=0-7`                                | Host CPU set passed to `taskset`.      |
| `QEMU_MEMORY=2g`                                  | Guest memory passed to QEMU.           |
| `QEMU_ACPI_TABLE_DIR`                             | Directory of SSDTs injected into QEMU. |
| `QEMU_ACPI_TABLES`                                | Comma-separated SSDTs to inject.       |
| `ACPI_AML_TABLE_DIR`                              | Firmware tables used by AML tests.     |

`QEMU_ACPI_TABLE_DIR` must contain files named `ssdtN.dat`. By default, all
matching files are injected in numeric order; `QEMU_ACPI_TABLES` restricts the
selection to names such as `ssdt3.dat,ssdt5.dat`. `ACPI_AML_TABLE_DIR` enables
the full firmware regression in `nativeTest` and must contain `dsdt.dat` plus
`ssdt1.dat` through `ssdt17.dat`.

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
