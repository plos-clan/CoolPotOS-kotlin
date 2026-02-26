# CoolPotOS Kotlin edition

## Build

**The build system is now migrated to Gradle for better maintainability and modern tooling support:**

- Supports kernel build, ISO packaging, and QEMU run
- Assets (Limine and OVMF) are not fetched from the internet
- Cross-platform compatible (Linux/macOS/Windows)

**Available Gradle tasks:**
- `./gradlew build`: Build kernel ELF (equivalent to `make kernel`)
- `./gradlew buildIso`: Build the UEFI ISO image (equivalent to `make iso`)
- `./gradlew run`: Run the ISO image in QEMU (equivalent to `make run`)
- `./gradlew clean`: Clean build outputs (equivalent to `make clean`)
- `./gradlew cleanAll`: Remove entire build directory (equivalent to `make distclean`)
- `./gradlew buildMlibc`: Build bundled mlibc (equivalent to `make mlibc`)
- `./gradlew dev`: Build and run in one command (development workflow)

**Quick start:**

**Build and run in one command**

```shell
./gradlew dev
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
