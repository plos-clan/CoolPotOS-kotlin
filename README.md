# CoolPotOS Kotlin

## Build

**The build system is now migrated to Gradle for better maintainability and modern tooling support:**

- Supports kernel build, ISO packaging, and QEMU run
- Assets (Limine and OVMF) are not fetched from the internet
- Cross-platform compatible (Linux/macOS/Windows)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF
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

**Note:** The Gradle build system replaces the Makefile completely while maintaining all existing functionality.

## License

This project is licensed under the [0BSD License](LICENSE).
