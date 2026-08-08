# CoolPotOS Kotlin

## Build

This project uses Gradle for kernel build, ISO packaging, and QEMU run.

- Supports kernel build, ISO packaging, and QEMU run
- Cross-platform compatible (Linux/macOS/Windows)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
- `./gradlew prepareUserland`: Build the CachyOS EROFS root filesystem
- `./gradlew buildIso`: Build the UEFI ISO image
- `./gradlew run`: Run the ISO image in QEMU
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
- Rootless Podman (for CachyOS userland packaging)
- `xorriso` (for ISO creation)
- `qemu-system-x86_64` (for emulation)
- Git and Gradle (included with Kotlin/Native)

The ISO contains a zstd-compressed CachyOS x86_64 root filesystem at
`/boot/cachyos-rootfs-x86_64.erofs`. The build pulls the latest official OCI
image through rootless Podman, installs the exact Bash/Coreutils runtime package
set into an empty root through the USTC Arch Linux and CachyOS mirrors, removes
development files, documentation, package metadata, and other build-time
content, then emits a read-only EROFS image with 1 MiB zstd pclusters, packed
fragments, and deduplication. The kernel reads its metadata and data on demand
and mounts a writable tmpfs overlay above it. The package set and rootfs pruning
rules are kept in `assets/userland.sh`; Gradle only invokes that script and
checks its EROFS output. Override the image with `-PuserlandImage=...` or
`USERLAND_IMAGE`. QEMU uses 2 GiB by default so the kernel can retain the
compressed rootfs module and allocate the writable overlay; override it with
`-PqemuMemory=...` or `QEMU_MEMORY`.

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

## License

This project is licensed under the [0BSD License](LICENSE).

## Dependency

* mlibc [managarm/mlibc](https://github.com/managarm/mlibc)
