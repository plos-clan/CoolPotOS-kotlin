# CoolPotOS Kotlin

## Build

This project uses Gradle for kernel build, ISO packaging, and QEMU run.

- Supports kernel build, ISO packaging, and QEMU run
- Cross-platform compatible (Linux/macOS/Windows)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
- `./gradlew downloadAlpineInitramfs`: Download and verify the Alpine Linux x86_64 initramfs
- `./gradlew prepareAlpineInitramfs`: Decompress the downloaded initramfs into raw CPIO
- `./gradlew buildIso`: Build the UEFI ISO image
- `./gradlew run`: Run the ISO image in QEMU
- `./gradlew clean`: Clean kernel build outputs
- `./gradlew cleanAll`: Remove entire build directory
- `./gradlew buildMlibc`: Build bundled mlibc

**Quick start:**

**Build and run in one command**

```shell
./gradlew run
```

**Or step by step**

```shell
./gradlew buildIso
./gradlew run
```

You need to install:
- Kotlin/Native (`konanc`, `cinterop`)
- Clang (`clang`, `clang++`)
- LLD (`ld.lld`)
- `xorriso` (for ISO creation)
- `qemu-system-x86_64` (for emulation)
- Git and Gradle (included with Kotlin/Native)

The ISO contains Alpine Linux 3.24.1's x86_64 `initramfs-lts` as an uncompressed
ASCII CPIO archive at `/boot/alpine-initramfs-x86_64`. Limine exposes it to the
kernel as a boot module. QEMU uses 512 MiB by default so the kernel can retain
the boot module while populating tmpfs; override it with `-PqemuMemory=...` or
the `QEMU_MEMORY` environment variable.

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
