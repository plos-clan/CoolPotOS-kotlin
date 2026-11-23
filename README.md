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
- Per-CPU weighted round-robin scheduler
- VDSO
- ACPI AML
- network & unix socket
- devtmpfs & procfs & sysfs & erofs & fuse3
- Signal Delivery and Handling
- Unified cgroup v2 hierarchy, threaded groups, freezer and pids controller
- Linux programs binary compatible (glibc)
- GC & Coroutine & runtime exception

## Build

This project uses Gradle for kernel build, GPT disk image packaging, and QEMU run.

- Supports kernel build, GPT disk image packaging, and QEMU run
- Targets x86_64 (portable Kotlin code and tests also compile for the JVM)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
- `./gradlew prepareUserland`: Build the CachyOS EROFS root filesystem
- `./gradlew buildImage`: Build the sparse UEFI GPT disk image
- `./gradlew run`: Run the disk image in QEMU
- `./gradlew jvmTest`: Run portable Kotlin unit tests on the host
- `./gradlew qemuTest`: Run portable and native tests inside the kernel
- `./gradlew benchmark`: Run portable benchmarks on the JVM
- `./gradlew qemuBenchmark`: Boot the production rootfs and run system benchmarks
- `./gradlew clean`: Clean kernel build outputs
- `./gradlew cleanAll`: Remove entire build directory
- `./gradlew buildMlibc`: Build bundled mlibc

**Quick start:**

Release mode is the default.

**Build and run in one command**

```shell
./gradlew run
```

**Or step by step**

```shell
./gradlew buildImage
./gradlew run
```

Common run options:
- Debug mode: `./gradlew run -PdebugMode=true`
- Headless COM1 serial console: `./gradlew run -Pconsole=ttyS0,115200n8 -PqemuDisplay=none`
- Attach to an existing host bridge: `./gradlew run -PqemuBridge=virbr0`
- Select storage transport: `./gradlew run -PstorageTransport=bot`

You need to install:
- Kotlin/Native (`konanc`, `cinterop`)
- Clang (`clang`, `clang++`)
- LLD (`ld.lld`)
- Rootless Podman (for userland packaging)
- Rust/Cargo, e2fsprogs, and fakeroot (for sparse GPT image creation)
- `qemu-system-x86_64` (for emulation)
- Git and Gradle (included with Kotlin/Native)

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
| `QEMU=qemu-system-x86_64`                         | QEMU executable.                       |
| `QEMU_DISPLAY=gtk`                                | QEMU display frontend.                 |
| `QEMU_BRIDGE`                                     | Existing host bridge interface (`-PqemuBridge`). Unset uses user networking. |
| `KONAN_TOOLROOT=~/.konan/dependencies/xxx`        | Kotlin/Native GNU toolchain root.      |
| `MLIBC_PREFIX=kernel/build/mlibc-x86_64/prefix`   | mlibc installation prefix.             |
| `USERLAND_IMAGE=docker.io/cachyos/cachyos:latest` | OCI image used to build the rootfs.    |
| `QEMU_CPU_SET=0-7`                                | Host CPU set passed to `taskset`.      |
| `QEMU_MEMORY=2g`                                  | Guest memory passed to QEMU.           |
| `ACPI_AML_TABLE_DIR`                              | Firmware tables used by AML tests.     |

## Kernel coroutines

Launch named kernel jobs with `KernelCoroutines.launch`. Children inherit a
multicore dispatcher with one worker, ready queue and deadline heap per CPU.
A continuation can resume on a different CPU after suspension.
Events wake a waiter without polling an event registry,
and timer insertion wakes only the owner of an earlier deadline.

```kotlin
import kotlinx.coroutines.delay
import org.plos_clan.cpos.coroutines.KernelCoroutines

KernelCoroutines.launch("example") {
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
