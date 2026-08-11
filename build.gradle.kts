import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.Project

@DisableCachingByDefault(because = "Downloads third-party artifacts")
abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val destinationFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destinationFile.get().asFile
        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.part")

        try {
            val connection = URI.create(sourceUrl.get()).toURL().openConnection()
            connection.setRequestProperty("User-Agent", "Gradle")
            connection.getInputStream().use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

private data class ToolSettings(
    val cc: String,
    val cxx: String,
    val linker: String,
    val objcopy: String,
    val xorriso: String,
    val qemu: String,
)

private data class Archive(
    val url: String,
    val file: File,
)

private object FullLto {
    val compilerArgs = listOf("-flto=full", "-funified-lto")
    val linkerArgs = listOf("--lto=full", "--lto-O3")
}

private class BuildPaths(project: Project) {
    val root = project.layout.buildDirectory.get().asFile
    val iso = root.resolve("iso")
    val downloads = root.resolve("downloads")
    val kernelC = project.file("kernel/c")
    val kernelKotlin = project.file("kernel/kotlin")
    val assets = project.file("assets")
    val libraries = project.file("lib")
    val mlibc = project.file("mlibc")

    val limine = root.resolve("limine")
    val limineInclude = limine.resolve("include")
    val limineHeader = limineInclude.resolve("limine.h")
    val limineUefi = limine.resolve("boot/limine-uefi-cd.bin")
    val limineEfi = limine.resolve("boot/BOOTX64.EFI")
    val limineArchive = Archive(
        "https://github.com/limine-bootloader/limine/archive/refs/heads/v11.x-binary.tar.gz",
        downloads.resolve("limine-v11.x-binary.tar.gz"),
    )
    val limineProtocol = Archive(
        "https://github.com/limine-bootloader/limine-protocol/archive/refs/heads/trunk.tar.gz",
        downloads.resolve("limine-protocol-trunk.tar.gz"),
    )

    val freestanding = root.resolve("freestnd-c-hdrs")
    val freestandingInclude = freestanding.resolve("include")
    val freestandingArchive = Archive(
        "https://github.com/osdev0/freestnd-c-hdrs-0bsd/archive/refs/heads/trunk.tar.gz",
        downloads.resolve("freestnd-c-hdrs-0bsd-trunk.tar.gz"),
    )

    val linkerScript = assets.resolve("linker.ld")
    val bridgeDef = kernelC.resolve("bridge.def")
    val userlandScript = assets.resolve("userland.sh")
    val mlibcPatch = assets.resolve("mlibc.patch")
    val mlibcSyscallHeader = mlibc.resolve("sysdeps/template/include/sys/syscall.h")
    val cObjects = root.resolve("c-objects")
    val vdso = root.resolve("vdso")
    val kernelElf = root.resolve("kernel.elf")
    val isoImage = root.resolve("${project.name}.iso")
}

private class VdsoConfig(
    paths: BuildPaths,
    tools: ToolSettings,
    arch: String,
) {
    val source = paths.kernelC.resolve("vdso.c")
    val header = paths.kernelC.resolve("vdso.h")
    val linkerScript = paths.kernelC.resolve("vdso.ld")
    val objectFile = paths.vdso.resolve("vdso.o")
    val linkedImage = paths.vdso.resolve("vdso.unstripped.so")
    val image = paths.vdso.resolve("vdso.so")
    val blob = paths.vdso.resolve("vdso-blob.o")
    val compileCommand = listOf(
        tools.cc,
        "-target", "$arch-freestanding",
        "-std=c23", "-O3",
    ) + FullLto.compilerArgs + listOf(
        "-fPIC", "-fvisibility=hidden",
        "-ffreestanding", "-nostdinc", "-fno-stack-protector", "-fomit-frame-pointer",
        "-fno-asynchronous-unwind-tables", "-fno-unwind-tables",
        "-Wall", "-Wextra", "-Wpedantic", "-Werror",
        "-I${paths.freestandingInclude.absolutePath}",
        "-c", source.absolutePath,
        "-o", objectFile.absolutePath,
    )
    val linkCommand = listOf(tools.linker) + FullLto.linkerArgs + listOf(
        "-shared", "-nostdlib", "--hash-style=sysv",
        "-soname=linux-vdso.so.1", "-z", "max-page-size=0x1000", "-z", "noexecstack",
        "--build-id=none", "--orphan-handling=error",
        "-T", linkerScript.absolutePath,
        "-o", linkedImage.absolutePath,
        objectFile.absolutePath,
    )
    val stripCommand = listOf(
        tools.objcopy,
        "--strip-sections",
        linkedImage.absolutePath,
        image.absolutePath,
    )
    val embedCommand = listOf(
        tools.linker,
        "-r", "-m", "elf_$arch", "-b", "binary",
        "-o", blob.name,
        image.name,
    )
}

private data class UserlandConfig(
    val image: String,
    val platform: String,
    val name: String,
    val script: File,
    val archive: File,
)

private class MlibcConfig(
    paths: BuildPaths,
    tools: ToolSettings,
    arch: String,
    debug: Boolean,
    val prefix: File,
) {
    val source = paths.mlibc
    val build = paths.root.resolve("mlibc-$arch")
    val crossFile = build.resolve("cross_file.txt")
    val buildType = if (debug) "debug" else "release"
    val path = "${build.absolutePath}:${System.getenv("PATH").orEmpty()}"
    val cc = "${tools.cc} -target $arch-unknown-none"
    val cxx = "${tools.cxx} -target $arch-unknown-none"
    val cFlags = (
        listOf(
            "-pipe",
            "-Wall", "-Wextra", "-nostdinc", "-ffreestanding",
            "-fno-stack-protector", "-fno-stack-check",
        ) + FullLto.compilerArgs + listOf(
            "-fno-PIC",
            "-ffunction-sections", "-fdata-sections",
            "-m64", "-march=x86-64", "-mno-red-zone", "-mcmodel=kernel",
            "-D__thread=''", "-D_Thread_local=''", "-D_GNU_SOURCE",
        )
    ).joinToString(" ")
    val cxxFlags = "$cFlags -fno-rtti -fno-exceptions -fno-sized-deallocation"
    val libraries = listOf("libc.a", "libm.a", "libpthread.a")
        .map(prefix.resolve("lib")::resolve)

    fun run(command: List<String>, quiet: Boolean = false): Boolean =
        ProcessBuilder(command).apply {
            directory(build)
            environment()["PATH"] = path
            if (quiet) {
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
                redirectError(ProcessBuilder.Redirect.DISCARD)
            } else {
                inheritIO()
            }
        }.start().waitFor() == 0
}

private class KernelConfig(
    paths: BuildPaths,
    mlibc: MlibcConfig,
    vdso: VdsoConfig,
    tools: ToolSettings,
    arch: String,
    debug: Boolean,
    toolRoot: File,
) {
    val sources = listOf(
        "boot.c", "shim.c", "clock.c", "syscall.c", "gdt.c",
        "idt.c", "handoff.c", "smp.c", "zstd_bridge.c",
    ).map(paths.kernelC::resolve)
    val objects = sources.map { paths.cObjects.resolve("${it.nameWithoutExtension}.o") }
    val kotlinLinkTask = if (debug) "linkDebugStaticNative" else "linkReleaseStaticNative"
    val kotlinLibrary = paths.root.resolve(
        "bin/native/${if (debug) "debugStatic" else "releaseStatic"}/libkernel.a",
    )
    val staticLibraries = listOf(
        "libos_terminal-embedfont-x86_64.a",
        "libzstd-decompress-x86_64.a",
    ).map(paths.libraries::resolve)
    val runtimeLibraries = mlibc.libraries + listOf(
        File(toolRoot, "$arch-unknown-linux-gnu/sysroot/lib/libstdc++.a"),
        File(toolRoot, "lib/gcc/$arch-unknown-linux-gnu/8.3.0/libgcc.a"),
        File(toolRoot, "lib/gcc/$arch-unknown-linux-gnu/8.3.0/libgcc_eh.a"),
    )
    val linkInputs = objects + vdso.blob + kotlinLibrary + runtimeLibraries + staticLibraries
    val compileArgs = listOf(
        "-target", "$arch-freestanding",
        "-std=c23", "-ffreestanding", "-nostdinc", "-fno-builtin",
    ) + FullLto.compilerArgs + listOf(
        "-m64", "-mno-red-zone", "-mcmodel=kernel", "-fno-stack-protector",
        "-mno-80387", "-mno-mmx", "-mno-sse", "-mno-sse2",
        "-Wall", "-Wextra", "-Wpedantic", "-Werror",
    ) + listOf(
        paths.kernelC,
        paths.root,
        paths.mlibcSyscallHeader.parentFile,
        paths.limineInclude,
        paths.freestandingInclude,
    ).map { "-I${it.absolutePath}" } + if (debug) listOf("-Og") else listOf("-O3")
    val linkArgs = FullLto.linkerArgs + listOf(
        "-m", "elf_$arch", "-nostdlib", "--eh-frame-hdr",
        "-z", "max-page-size=0x1000", "--gc-sections",
        "-u", "sched_yield", "-u", "frg_panic",
        "-T", paths.linkerScript.absolutePath,
    )
    val linker = tools.linker
}

private data class QemuConfig(
    val executable: String,
    val cpuSet: String,
    val flags: List<String>,
)

private class BuildConfig(private val project: Project) {
    val arch = "x86_64"
    val debug = settingBoolean("debugMode", "DEBUG_MODE", false)
    val paths = BuildPaths(project)
    val tools = ToolSettings(
        cc = setting("crossCc", "CROSS_CC", "clang"),
        cxx = setting("crossCxx", "CROSS_CXX", "clang++"),
        linker = setting("linker", "LINKER", "ld.lld"),
        objcopy = setting("objcopy", "OBJCOPY", "llvm-objcopy"),
        xorriso = setting("xorriso", "XORRISO", "xorriso"),
        qemu = setting("qemu", "QEMU", "qemu-system-x86_64"),
    )
    private val toolRoot = setting(
        "konanToolRoot",
        "KONAN_TOOLROOT",
        "${System.getenv("KONAN_HOME") ?: "${System.getProperty("user.home")}/.konan"}/" +
            "dependencies/$arch-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2",
    ).let(::File)
    val mlibc = MlibcConfig(
        paths,
        tools,
        arch,
        debug,
        project.file(
            setting(
                "mlibcPrefix",
                "MLIBC_PREFIX",
                paths.root.resolve("mlibc-$arch/prefix").path,
            ),
        ),
    )
    val vdso = VdsoConfig(paths, tools, arch)
    private val rootfsName = "cachyos-rootfs-$arch.erofs"
    val kernel = KernelConfig(paths, mlibc, vdso, tools, arch, debug, toolRoot)
    val userland = UserlandConfig(
        image = setting(
            "userlandImage",
            "USERLAND_IMAGE",
            "docker.io/cachyos/cachyos:latest",
        ),
        platform = "linux/amd64",
        name = rootfsName,
        script = paths.userlandScript,
        archive = paths.root.resolve("generated/userland/$rootfsName"),
    )
    val qemu = QemuConfig(
        executable = tools.qemu,
        cpuSet = setting("qemuCpuSet", "QEMU_CPU_SET", "0-7"),
        flags = listOf(
            "-m", setting("qemuMemory", "QEMU_MEMORY", "2g"),
            "-M", "q35", "-cpu", "host", "-enable-kvm",
            "-no-reboot", "-smp", "4",
            "-drive",
            "if=pflash,format=raw,readonly=on,file=${paths.assets.resolve("ovmf-code.fd")}",
        ) + if (debug) listOf("-s", "-S") else emptyList(),
    )

    private fun setting(prop: String, env: String, default: String): String = listOfNotNull(
        (project.findProperty(prop) as String?)?.takeIf(String::isNotBlank),
        System.getenv(env)?.takeIf(String::isNotBlank),
    ).firstOrNull() ?: default

    private fun settingBoolean(prop: String, env: String, default: Boolean): Boolean =
        when (val value = setting(prop, env, default.toString()).lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> throw GradleException("Expected boolean for $prop/$env, got '$value'.")
        }
}

private val config = BuildConfig(project)

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")

    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.binaries.staticLib {
        baseName = "kernel"
        if (config.debug) {
            freeCompilerArgs += "-g"
        }
    }

    sourceSets.named("nativeMain") {
        kotlin.srcDir(config.paths.kernelKotlin)
        dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }

    nativeTarget.compilations.getByName("main").cinterops {
        create("bridge") {
            defFile(config.paths.bridgeDef)
            packageName("bridge")
            includeDirs(
                config.paths.kernelC,
                config.paths.limineInclude,
                config.paths.freestandingInclude,
            )
        }
    }
}

val downloadLimine = tasks.register<DownloadFileTask>("downloadLimine") {
    group = "build"
    description = "Downloads Limine bootloader assets."
    sourceUrl.set(config.paths.limineArchive.url)
    destinationFile.set(config.paths.limineArchive.file)
}

val downloadLimineProtocol = tasks.register<DownloadFileTask>("downloadLimineProtocol") {
    group = "build"
    description = "Downloads limine-protocol headers."
    sourceUrl.set(config.paths.limineProtocol.url)
    destinationFile.set(config.paths.limineProtocol.file)
}

val downloadFreestndHeaders = tasks.register<DownloadFileTask>("downloadFreestndHeaders") {
    group = "build"
    description = "Downloads freestanding C headers."
    sourceUrl.set(config.paths.freestandingArchive.url)
    destinationFile.set(config.paths.freestandingArchive.file)
}

val prepareUserland = tasks.register<Exec>("prepareUserland") {
    group = "build"
    description = "Builds a zstd-compressed CachyOS EROFS root filesystem."

    inputs.property("image", config.userland.image)
    inputs.property("platform", config.userland.platform)
    inputs.file(config.userland.script).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(config.userland.archive)

    commandLine(
        listOf(
            "podman",
            "run", "--rm", "--pull=newer",
            "--platform", config.userland.platform,
            "--volume", "${config.userland.archive.parentFile.absolutePath}:/output:rw,Z",
            "--volume", "${config.userland.script.absolutePath}:/usr/local/bin/cpos-userland:ro,Z",
            config.userland.image,
            "/usr/local/bin/cpos-userland",
            config.userland.name,
        )
    )
}

val prepareFreestndHeaders = tasks.register<Sync>("prepareFreestndHeaders") {
    group = "build"
    description = "Extracts all freestanding C headers."
    dependsOn(downloadFreestndHeaders)

    into(config.paths.freestanding)
    from({
        tarTree(resources.gzip(config.paths.freestandingArchive.file))
    }) {
        include("*/include/**")
        eachFile {
            path = path.substringAfter('/')
        }
        includeEmptyDirs = false
    }
}

val prepareLimine = tasks.register<Sync>("prepareLimine") {
    group = "build"
    description = "Extracts Limine boot binary and protocol header."
    dependsOn(downloadLimine, downloadLimineProtocol)

    into(config.paths.limine)
    from({
        tarTree(resources.gzip(config.paths.limineArchive.file))
    }) {
        include("*/limine-uefi-cd.bin", "*/BOOTX64.EFI")
        eachFile { path = "boot/$name" }
        includeEmptyDirs = false
    }
    from({
        tarTree(resources.gzip(config.paths.limineProtocol.file))
    }) {
        include("*/include/limine.h")
        eachFile {
            if (name == "limine.h") {
                path = "include/limine.h"
            }
        }
        includeEmptyDirs = false
    }
}

tasks.matching { it.name == "cinteropBridgeNative" }.configureEach {
    dependsOn(prepareLimine, prepareFreestndHeaders)
}

val compileVdso = tasks.register<Exec>("compileVdso") {
    group = "build"
    description = "Compiles the userspace vDSO."
    notCompatibleWithConfigurationCache("Creates a generated native image.")
    dependsOn(prepareFreestndHeaders)
    inputs.file(config.vdso.source)
    inputs.file(config.vdso.header)
    inputs.dir(config.paths.freestandingInclude)
    outputs.file(config.vdso.objectFile)
    doFirst { config.paths.vdso.mkdirs() }
    commandLine(config.vdso.compileCommand)
}

val linkVdso = tasks.register<Exec>("linkVdso") {
    group = "build"
    description = "Links the userspace vDSO ELF image."
    notCompatibleWithConfigurationCache("Creates a generated native image.")
    dependsOn(compileVdso)
    inputs.file(config.vdso.objectFile)
    inputs.file(config.vdso.linkerScript)
    outputs.file(config.vdso.linkedImage)
    commandLine(config.vdso.linkCommand)
}

val stripVdso = tasks.register<Exec>("stripVdso") {
    group = "build"
    description = "Removes link-time metadata from the vDSO image."
    notCompatibleWithConfigurationCache("Creates a generated native image.")
    dependsOn(linkVdso)
    inputs.file(config.vdso.linkedImage)
    outputs.file(config.vdso.image)
    commandLine(config.vdso.stripCommand)
}

val embedVdso = tasks.register<Exec>("embedVdso") {
    group = "build"
    description = "Embeds the vDSO image into the kernel link."
    notCompatibleWithConfigurationCache("Creates a generated native image.")
    dependsOn(stripVdso)
    inputs.file(config.vdso.image)
    outputs.file(config.vdso.blob)
    workingDir(config.paths.vdso)
    commandLine(config.vdso.embedCommand)
}

val buildMlibc = tasks.register("buildMlibc") {
    group = "build"
    description = "Builds the mlibc C library."
    notCompatibleWithConfigurationCache("Runs an external source build.")

    inputs.property("buildType", config.mlibc.buildType)
    inputs.property("compilerFlags", listOf(config.mlibc.cFlags, config.mlibc.cxxFlags))
    inputs.file(config.paths.mlibcPatch)
    inputs.dir(config.mlibc.source)
    outputs.dir(config.mlibc.prefix)

    doLast {
        with(config.mlibc) {
            build.deleteRecursively()
            check(build.mkdirs()) { "Failed to create ${build.path}" }

            listOf(
                Triple("cc", cc, cFlags),
                Triple("c++", cxx, cxxFlags),
            ).forEach { (name, compiler, flags) ->
                build.resolve(name).apply {
                    writeText("#!/bin/sh\n$compiler $flags \"\$@\"\n")
                    check(setExecutable(true)) { "Failed to make $name executable" }
                }
            }

            crossFile.writeText(
                """
                [binaries]
                c = 'cc'
                cpp = 'c++'

                [host_machine]
                system = 'template'
                cpu_family = '${config.arch}'
                cpu = '${config.arch}'
                endian = 'little'
                """.trimIndent()
            )

            val applyPatch = listOf("git", "-C", source.path, "apply")
            check(
                run(applyPatch + config.paths.mlibcPatch.path, quiet = true) ||
                    run(
                        applyPatch + listOf("-R", "--check", config.paths.mlibcPatch.path),
                        quiet = true,
                    )
            ) { "Failed to apply ${config.paths.mlibcPatch.name}" }

            val meson = listOf(
                "meson", "setup", source.path,
                "--cross-file", crossFile.path,
                "--buildtype=$buildType",
                "--prefix=${prefix.path}",
                "-Ddefault_library=static",
                "-Dlibgcc_dependency=false",
                "-Duse_freestnd_hdrs=enabled",
            )
            check(run(meson)) { "meson setup failed" }
            check(run(listOf("ninja", "-v"))) { "ninja build failed" }
            check(run(listOf("ninja", "install"))) { "ninja install failed" }
        }
    }
}

val compileC = tasks.register("compileC") {
    group = "build"
    description = "Compiles C sources into object files."
    dependsOn(prepareLimine, prepareFreestndHeaders, buildMlibc)
    notCompatibleWithConfigurationCache("Runs an external compiler.")

    inputs.property("compiler", config.tools.cc)
    inputs.property("compileArgs", config.kernel.compileArgs)
    inputs.files(config.kernel.sources)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        config.paths.kernelC.resolve("bridge.h"),
        config.vdso.header,
        config.paths.limineHeader,
        config.paths.mlibcSyscallHeader,
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(config.paths.freestandingInclude)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(config.paths.cObjects)

    doLast {
        config.paths.cObjects.mkdirs()
        config.kernel.sources.forEach { source ->
            val objectFile = config.paths.cObjects.resolve("${source.nameWithoutExtension}.o")
            val command = listOf(config.tools.cc) + config.kernel.compileArgs + listOf(
                "-c", source.absolutePath,
                "-o", objectFile.absolutePath,
            )
            check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) {
                "Failed to compile ${source.name}"
            }
        }
    }
}

val linkKernel = tasks.register<Exec>("linkKernel") {
    group = "build"
    description = "Links the kernel and runtime libraries into an ELF executable."
    dependsOn(config.kernel.kotlinLinkTask, compileC, embedVdso, buildMlibc)

    inputs.files(config.kernel.linkInputs)
    inputs.file(config.paths.linkerScript)
    outputs.file(config.paths.kernelElf)

    val linkCommand = buildList {
        add(config.kernel.linker)
        addAll(config.kernel.linkArgs)
        add("-o")
        add(config.paths.kernelElf.absolutePath)
        addAll(config.kernel.objects.map(File::getAbsolutePath))
        add(config.vdso.blob.absolutePath)
        add(config.kernel.kotlinLibrary.absolutePath)
        add("--start-group")
        addAll(config.kernel.staticLibraries.map(File::getAbsolutePath))
        addAll(config.kernel.runtimeLibraries.map(File::getAbsolutePath))
        add("--end-group")
    }
    commandLine(linkCommand)
}

tasks.named("build") {
    dependsOn(linkKernel)
}

val stageIso = tasks.register<Sync>("stageIso") {
    group = "build"
    description = "Stages the kernel, EROFS root filesystem, and Limine assets into the ISO directory."
    dependsOn(linkKernel, prepareLimine, prepareUserland)

    into(config.paths.iso)
    from(config.paths.assets.resolve("limine.conf")) { into("limine") }
    from(config.paths.limineUefi) { into("limine") }
    from(config.paths.limineEfi) { into("EFI/BOOT") }
    from(config.userland.archive) { into("boot") }
    from(config.paths.kernelElf)
}

val buildIso = tasks.register<Exec>("buildIso") {
    group = "build"
    description = "Builds the UEFI ISO image from staged assets."
    dependsOn(stageIso)

    inputs.dir(config.paths.iso)
    outputs.file(config.paths.isoImage)

    val isoCommand = buildList {
        add(config.tools.xorriso)
        addAll(listOf(
            "-as", "mkisofs",
            "--efi-boot", "limine/limine-uefi-cd.bin",
            "-efi-boot-part", "--efi-boot-image",
        ))
        add(config.paths.iso.absolutePath)
        add("-o")
        add(config.paths.isoImage.absolutePath)
    }
    commandLine(isoCommand)
}

tasks.register<Exec>("run") {
    group = "build"
    description = "Runs CoolPotOS in QEMU with serial on stdio."
    dependsOn(buildIso)

    val runCommand = buildList {
        addAll(listOf("taskset", "--cpu-list", config.qemu.cpuSet))
        add(config.qemu.executable)
        addAll(config.qemu.flags)
        add("-serial")
        add("stdio")
        add(config.paths.isoImage.absolutePath)
    }
    commandLine(runCommand)
}

tasks.named<Delete>("clean") {
    description = "Deletes kernel build artifacts while preserving mlibc build outputs."
    setDelete(
        fileTree(config.paths.root) {
            exclude(config.mlibc.build.name, "${config.mlibc.build.name}/**")
        }
    )
}

tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "Deletes all build artifacts, including mlibc."
    delete(config.paths.root)
}
