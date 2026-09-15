import groovy.json.JsonSlurper
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.StaticLibrary
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

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

@CacheableTask
abstract class GzipFileTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:OutputFile
    abstract val destinationFile: RegularFileProperty

    @TaskAction
    fun compress() {
        val source = sourceFile.get().asFile
        val target = destinationFile.get().asFile
        target.parentFile.mkdirs()

        source.inputStream().buffered().use { input ->
            BestCompressionGzipStream(target.outputStream().buffered()).use(input::copyTo)
        }
    }

    private class BestCompressionGzipStream(output: OutputStream) :
        GZIPOutputStream(output, DEFAULT_BUFFER_SIZE) {
        init {
            def.setLevel(Deflater.BEST_COMPRESSION)
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxBenchmark)
    alias(libs.plugins.kotlinAllOpen)
}

allOpen { annotation("org.openjdk.jmh.annotations.State") }

@CacheableTask
abstract class KernelBitcodeTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFile: RegularFileProperty

    @get:Input
    abstract val llvmLink: Property<String>

    @get:Input
    abstract val compileCommand: ListProperty<String>

    @get:OutputFile
    abstract val destinationFile: RegularFileProperty

    @TaskAction
    fun compile() {
        val linked = temporaryDir.resolve("linked.ll")
        val freestanding = temporaryDir.resolve("freestanding.ll")
        val output = destinationFile.get().asFile
        exec.exec {
            commandLine(llvmLink.get(), "-S", sourceFile.get().asFile, "-o", linked)
        }
        freestanding.bufferedWriter().use { writer ->
            linked.forEachLine { line ->
                writer.appendLine(
                    if (line.startsWith("attributes #") && "\"no-builtins\"" !in line)
                        line.replaceFirst("{", "{ \"no-builtins\"")
                    else line
                )
            }
        }
        output.parentFile.mkdirs()
        exec.exec {
            commandLine(compileCommand.get() + listOf(freestanding.absolutePath, "-o", output.absolutePath))
        }
    }
}

private data class ToolSettings(
    val cc: String,
    val cxx: String,
    val linker: String,
    val llvmLink: String,
    val objcopy: String,
    val xorriso: String,
    val qemu: String,
)

private data class RemoteArtifact(
    val url: String,
    val file: File,
)

private object FullLto {
    val compilerArgs = listOf("-flto=full", "-funified-lto")
    fun linkerArgs(debug: Boolean = false) = listOf("--lto=full", if (debug) "--lto-O0" else "--lto-O3")
}

private class BuildPaths(project: Project) {
    val root = project.layout.buildDirectory.get().asFile
    val iso = root.resolve("iso")
    val downloads = root.resolve("downloads")
    val kernelC = project.file("src/nativeMain/c")
    val assets = project.rootProject.file("assets")
    val libraries = project.rootProject.file("prebuilt/x86_64")
    val mlibc = project.rootProject.file("vendor/mlibc")

    val limine = root.resolve("limine")
    val limineInclude = limine.resolve("include")
    val limineHeader = limineInclude.resolve("limine.h")
    val limineUefi = limine.resolve("boot/limine-uefi-cd.bin")
    val limineEfi = limine.resolve("boot/BOOTX64.EFI")
    val liminePrebuilt = RemoteArtifact(
        "https://github.com/Limine-Bootloader/Limine/releases/latest/download/limine-binary.tar.gz",
        downloads.resolve("limine-12.x-binary.tar.gz"),
    )
    val limineProtocolHeader = RemoteArtifact(
        "https://raw.githubusercontent.com/Limine-Bootloader/limine-protocol/trunk/include/limine.h",
        downloads.resolve("limine-protocol-trunk.h"),
    )

    val freestanding = root.resolve("freestnd-c-hdrs")
    val freestandingInclude = freestanding.resolve("include")
    val freestandingArchive = RemoteArtifact(
        "https://github.com/osdev0/freestnd-c-hdrs-0bsd/archive/refs/heads/trunk.tar.gz",
        downloads.resolve("freestnd-c-hdrs-0bsd-trunk.tar.gz"),
    )

    val linkerScript = assets.resolve("linker.ld")
    val bridgeDef = kernelC.resolve("bridge.def")
    val userlandScript = assets.resolve("userland.sh")
    val initScript = assets.resolve("init")
    val mlibcPatch = assets.resolve("mlibc.patch")
    val mlibcSyscallHeader = mlibc.resolve("sysdeps/template/include/sys/syscall.h")
    val cObjects = root.resolve("c-objects")
    val vdso = root.resolve("vdso")
    val kernelElf = root.resolve("kernel.elf")
    val kernelGzip = root.resolve("kernel.elf.gz")
    val isoImage = root.resolve("${project.rootProject.name}.iso")
}

private class VdsoConfig(
    paths: BuildPaths,
    tools: ToolSettings,
    arch: String,
) {
    val source = paths.kernelC.resolve("vdso.c")
    val linkerScript = paths.kernelC.resolve("vdso.ld")
    val objectFile = paths.vdso.resolve("vdso.o")
    val linkedImage = paths.vdso.resolve("vdso.unstripped.so")
    val image = paths.vdso.resolve("vdso.so")
    val blob = paths.vdso.resolve("vdso-blob.o")
    val compileCommand = listOf(
        tools.cc,
        "-target", "$arch-freestanding",
        "-std=c23", "-ffreestanding", "-fPIC", "-fno-stack-protector", "-fomit-frame-pointer",
        "-O3", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
        "-c", source.absolutePath,
        "-o", objectFile.absolutePath,
    )
    val linkCommand = listOf(tools.linker) + FullLto.linkerArgs() + listOf(
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
            "-Wall", "-Wextra", "-nostdlibinc", "-ffreestanding",
            "-fno-stack-protector", "-fno-stack-check",
        ) + FullLto.compilerArgs + listOf(
            "-fno-PIC",
            "-ffunction-sections", "-fdata-sections",
            "-m64", "-march=x86-64", "-mno-red-zone", "-mcmodel=kernel",
            "-D__thread=''", "-D_Thread_local=''", "-D_GNU_SOURCE",
        )
    ).joinToString(" ")
    val cxxFlags = "$cFlags -nostdinc++ -fno-rtti -fno-exceptions -fno-sized-deallocation"
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
    arch: String,
    debug: Boolean,
    toolRoot: File,
) {
    val sources = listOf(
        "boot.c", "shim.c", "clock.c", "syscall.c", "gdt.c",
        "idt.c", "handoff.c", "smp.c", "tls.c", "zstd_bridge.c",
    ).map(paths.kernelC::resolve)
    val objects = sources.map { paths.cObjects.resolve("${it.nameWithoutExtension}.o") }
    val kotlinBitcode = paths.root.resolve("kernel.bc")
    val staticLibraries = listOf(
        "libos_terminal.a",
        "libzstd-decompress.a",
    ).map(paths.libraries::resolve)
    val runtimeLibraries = mlibc.libraries + listOf(
        File(toolRoot, "$arch-unknown-linux-gnu/sysroot/lib/libstdc++.a"),
        File(toolRoot, "lib/gcc/$arch-unknown-linux-gnu/8.3.0/libgcc.a"),
        File(toolRoot, "lib/gcc/$arch-unknown-linux-gnu/8.3.0/libgcc_eh.a"),
    )
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
        mlibc.prefix.resolve("include"),
        paths.mlibc.resolve("sysdeps/template/include"),
        paths.freestandingInclude,
    ).map { "-I${it.absolutePath}" } + if (debug) listOf("-Og") else listOf("-O3")
    val linkArgs = FullLto.linkerArgs(debug) + listOf(
        "-m", "elf_$arch", "-nostdlib", "--eh-frame-hdr",
        "-z", "max-page-size=0x1000", "--gc-sections",
        "-u", "sched_yield", "-u", "frg_panic", "-u", "pthread_exit",
        "--wrap=__rtld_allocateTcb", "--wrap=pthread_create", "--wrap=pthread_join", "--wrap=pthread_detach",
        "-T", paths.linkerScript.absolutePath,
    )
}

private data class QemuConfig(
    val executable: String,
    val cpuSet: String,
    val flags: List<String>,
)

private data class BenchmarkSettings(val warmups: Int, val iterations: Int, val iterationMillis: Long) {
    init {
        require(warmups >= 0 && iterations > 0 && warmups <= Int.MAX_VALUE - iterations)
        require(iterationMillis in 1..Long.MAX_VALUE / 1_000_000L)
    }
}

private class BuildConfig(private val project: Project) {
    val arch = "x86_64"
    val debug = settingBoolean("debugMode", "DEBUG_MODE", false)
    val benchmark = BenchmarkSettings(
        setting("benchmarkWarmups", "BENCHMARK_WARMUPS", "5").toInt(),
        setting("benchmarkIterations", "BENCHMARK_ITERATIONS", "10").toInt(),
        setting("benchmarkIterationMillis", "BENCHMARK_ITERATION_MILLIS", "1000").toLong(),
    )
    val console = setting("console", "CONSOLE", "fb0")
    val paths = BuildPaths(project)
    val tools = ToolSettings(
        cc = setting("crossCc", "CROSS_CC", "clang"),
        cxx = setting("crossCxx", "CROSS_CXX", "clang++"),
        linker = setting("linker", "LINKER", "ld.lld"),
        llvmLink = setting("llvmLink", "LLVM_LINK", "llvm-link"),
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
        project.rootProject.file(
            setting(
                "mlibcPrefix",
                "MLIBC_PREFIX",
                paths.root.resolve("mlibc-$arch/prefix").path,
            ),
        ),
    )
    val vdso = VdsoConfig(paths, tools, arch)
    private val rootfsName = "rootfs-$arch.erofs"
    val kernel = KernelConfig(paths, mlibc, vdso, arch, debug, toolRoot)
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
    private val qemuBridge = optionalSetting("qemuBridge", "QEMU_BRIDGE")
    val qemu = QemuConfig(
        executable = tools.qemu,
        cpuSet = setting("qemuCpuSet", "QEMU_CPU_SET", "0-7"),
        flags = listOf(
            "-m", setting("qemuMemory", "QEMU_MEMORY", "2g"),
            "-M", "q35", "-cpu", "host", "-enable-kvm",
            "-no-reboot", "-smp", setting("qemuSmp", "QEMU_SMP", "4"),
            "-device", "qemu-xhci,id=xhci",
            "-device", "usb-kbd,bus=xhci.0", "-device", "usb-mouse,bus=xhci.0",
            "-netdev", qemuBridge?.let { "bridge,id=usbnet,br=$it" } ?: "user,id=usbnet",
            "-device", "usb-net,id=rndis,bus=xhci.0,netdev=usbnet",
            "-display", setting("qemuDisplay", "QEMU_DISPLAY", "gtk"),
            "-chardev", "stdio,id=console,mux=on,signal=off",
            "-serial", "chardev:console",
            "-drive",
            "if=pflash,format=raw,readonly=on,file=${paths.assets.resolve("ovmf-code.fd")}",
        ) + (if (debug) listOf("-s", "-S") else emptyList()) + listOf(
            "-drive",
            "file=${paths.isoImage.absolutePath},format=raw,snapshot=on",
        ),
    )

    private fun setting(prop: String, env: String, default: String): String =
        optionalSetting(prop, env) ?: default

    private fun optionalSetting(prop: String, env: String): String? =
        (project.findProperty(prop) as String?)?.takeIf(String::isNotBlank)
            ?: System.getenv(env)?.takeIf(String::isNotBlank)

    private fun settingBoolean(prop: String, env: String, default: Boolean): Boolean =
        when (val value = setting(prop, env, default.toString()).lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> throw GradleException("Expected boolean for $prop/$env, got '$value'.")
        }
}

private val config = BuildConfig(project)

val runtimeCallbacks = layout.buildDirectory.file("runtime-callbacks.bc")
val compileRuntimeCallbacks = tasks.register<Exec>("compileRuntimeCallbacks") {
    val source = config.paths.kernelC.resolve("callback.c")
    inputs.file(source)
    inputs.dir(config.paths.freestandingInclude)
    inputs.property("compiler", config.tools.cc)
    inputs.property("compileArgs", config.kernel.compileArgs)
    outputs.file(runtimeCallbacks)
    commandLine(
        listOf(config.tools.cc) + config.kernel.compileArgs + listOf(
            "-c", "-emit-llvm", source.absolutePath,
            "-o", runtimeCallbacks.get().asFile.absolutePath,
        )
    )
}

val runtimeLayout = layout.buildDirectory.dir("generated/runtime-layout")
val generateRuntimeLayout = tasks.register("generateRuntimeLayout") {
    inputs.file(config.mlibc.build.resolve("compile_commands.json"))
    inputs.dir(config.mlibc.source.resolve("options"))
    inputs.dir(config.mlibc.source.resolve("subprojects/frigg/include"))
    outputs.dir(runtimeLayout)
    notCompatibleWithConfigurationCache("Reads the native compiler configuration.")
    doLast {
        val commands = JsonSlurper().parse(config.mlibc.build.resolve("compile_commands.json")) as List<*>
        val compilation = commands.filterIsInstance<Map<*, *>>().single {
            (it["file"] as String).endsWith("/internal/generic/threads.cpp")
        }
        val command = (compilation["command"] as String).substringBefore(" -MD ") +
            " -fsyntax-only -Xclang -fdump-record-layouts " + compilation["file"]
        val compiler = ProcessBuilder("sh", "-c", command).apply {
            directory(config.mlibc.build)
            environment()["PATH"] = config.mlibc.path
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        val records = compiler.inputStream.bufferedReader().readText()
            .split("*** Dumping AST Record Layout")
        check(compiler.waitFor() == 0) { "Cannot determine the runtime ABI" }
        val types = mapOf("Tcb" to "struct Tcb", "LocalKeys" to "struct frg::array<struct Tcb::LocalKey,")
        val source = buildString {
            appendLine("package org.plos_clan.cpos.tasks")
            types.forEach { (name, type) ->
                val record = records.single { it.lineSequence().firstOrNull { line -> '|' in line }
                    ?.substringAfter("| ").let { declaration ->
                        if (type.endsWith(',')) declaration?.startsWith(type) == true else declaration == type
                    }
                }
                val size = Regex("sizeof=(\\d+),.*?align=(\\d+)").find(record)!!.groupValues
                appendLine("internal object ${name}Layout {")
                appendLine("    const val SIZE = ${size[1]}uL")
                appendLine("    const val ALIGN = ${size[2]}uL")
                Regex("(?m)^\\s*(\\d+) \\|   \\S.*? (\\w+)$").findAll(record).forEach { field ->
                    appendLine("    const val ${field.groupValues[2]} = ${field.groupValues[1]}uL")
                }
                appendLine("}")
            }
        }
        runtimeLayout.get().file("RuntimeLayout.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(source)
        }
        runtimeLayout.get().file("runtime_layout.h").asFile.writeText(buildString {
            appendLine("#pragma once")
            var type = ""
            source.lineSequence().forEach { line ->
                if (line.startsWith("internal object ")) type = line.substringAfter("object ").substringBefore("Layout")
                if (line.trimStart().startsWith("const val ")) {
                    val declaration = line.substringAfter("const val ").replace(" = ", " ").removeSuffix("uL")
                    appendLine("#define ${type}_$declaration")
                }
            }
        })
    }
}

val kotlinBitcodeTasks = listOf("main", "qemuTest").associateWith { variant ->
    val title = variant.replaceFirstChar(Char::uppercaseChar)
    val taskName = if (variant == "main") "compileKotlinBitcode" else "compile${title}Bitcode"
    tasks.register<KernelBitcodeTask>(taskName) {
        group = "build"
        description = "Prepares Kotlin/Native bitcode for freestanding full LTO."
        llvmLink.set(config.tools.llvmLink)
        compileCommand.set(
            listOf(config.tools.cc) + FullLto.compilerArgs + listOf(
                "-target", "${config.arch}-freestanding", "-x", "ir", "-c", "-emit-llvm",
                "-Wno-override-module",
            )
        )
        val output = if (variant == "main") config.kernel.kotlinBitcode
            else config.paths.root.resolve("$variant/kernel.bc")
        destinationFile.set(output)
    }
}

kotlin {
    val jvmTarget = jvm()
    val nativeTarget = linuxX64("native")
    val commonMain = sourceSets.getByName("commonMain")
    val commonTest = sourceSets.getByName("commonTest")
    commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
    commonTest.dependencies { implementation(kotlin("test")) }
    val commonBenchmark = sourceSets.create("commonBenchmark") {
        dependencies { implementation(libs.kotlinx.benchmark.runtime) }
    }
    jvmTarget.compilations.getByName("main").defaultSourceSet.dependsOn(commonBenchmark)

    val qemuHost = jvmTarget.compilations.create("qemuHost") {
        defaultSourceSet {
            kotlin.srcDir("src/qemuHost/kotlin")
            dependencies { implementation(libs.kotlinx.serialization.json) }
        }
    }
    jvmTarget.compilations.getByName("test").associateWith(qemuHost)

    val userBenchmark = nativeTarget.compilations.create("qemuBenchmark") {
        defaultSourceSet {
            kotlin.srcDirs("src/qemuMain/kotlin", "src/qemuBenchmark/kotlin")
            dependencies { implementation(libs.kotlinx.io.core) }
        }
    }
    nativeTarget.binaries.executable("qemuBenchmark", listOf(NativeBuildType.RELEASE)) {
        compilation = userBenchmark
        baseName = "benchmark"
        linkerOpts("--as-needed")
    }

    nativeTarget.binaries.staticLib {
        if (buildType.debuggable == config.debug) {
            kotlinBitcodeTasks.getValue("main").configure {
                dependsOn(linkTaskProvider)
                sourceFile.set(outputFile)
            }
        }
    }

    val nativeMain = nativeTarget.compilations.getByName("main")
    nativeMain.cinterops.create("bridge") {
        defFile(config.paths.bridgeDef)
        includeDirs(
            config.paths.kernelC,
            config.paths.limineInclude,
            config.mlibc.prefix.resolve("include"),
            config.paths.mlibc.resolve("sysdeps/template/include"),
            config.paths.freestandingInclude,
        )
    }

    val qemuTestCommon = sourceSets.create("qemuTestCommon") {
        kotlin.srcDirs(commonMain.kotlin.sourceDirectories)
        dependencies { implementation(libs.kotlinx.coroutines.core) }
    }
    val qemuTest = nativeTarget.compilations.create("qemuTest") {
        defaultSourceSet {
            dependsOn(qemuTestCommon)
            kotlin.srcDirs("src/nativeMain/kotlin", "src/qemuMain/kotlin", "src/qemuTest/kotlin")
            kotlin.srcDir(files(runtimeLayout).builtBy(generateRuntimeLayout))
            kotlin.srcDirs(commonTest.kotlin.sourceDirectories)
            kotlin.srcDir("src/nativeTest/kotlin")
            dependencies { implementation(kotlin("test")) }
        }
        compileDependencyFiles += nativeMain.compileDependencyFiles
        compileTaskProvider.configure {
            compilerOptions.freeCompilerArgs.add("-generate-no-exit-test-runner")
        }
    }
    val testBuildType = if (config.debug) NativeBuildType.DEBUG else NativeBuildType.RELEASE
    nativeTarget.binaries.staticLib("qemuTest", listOf(testBuildType)) {
        compilation = qemuTest
        kotlinBitcodeTasks.getValue("qemuTest").configure {
            dependsOn(linkTaskProvider)
            sourceFile.set(outputFile)
        }
    }

    nativeTarget.binaries.withType<StaticLibrary>().configureEach {
        baseName = "kernel"
        freeCompilerArgs += listOf(
            "-native-library", runtimeCallbacks.get().asFile.absolutePath,
            "-Xoverride-clang-options=-cc1,-emit-llvm-bc,-disable-llvm-passes,-x,ir",
        )
        linkTaskProvider.configure {
            dependsOn(compileRuntimeCallbacks)
            inputs.file(runtimeCallbacks)
        }
        if (buildType.debuggable) {
            freeCompilerArgs += listOf("-g", "-Xruntime-logs=gc=info")
        }
    }

    sourceSets.named("nativeMain") {
        dependsOn(commonMain)
        kotlin.srcDir("src/kernelMain/kotlin")
        kotlin.srcDir(files(runtimeLayout).builtBy(generateRuntimeLayout))
    }

    sourceSets.named("nativeTest") {
        dependsOn(commonTest)
        dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named("jvmTest") {
    inputs.property("acpiAmlTableDir", System.getenv("ACPI_AML_TABLE_DIR") ?: "")
}

benchmark {
    targets.register("jvm")

    configurations.named("main") {
        warmups = config.benchmark.warmups
        iterations = config.benchmark.iterations
        iterationTime = config.benchmark.iterationMillis
        iterationTimeUnit = "ms"
        mode = "avgt"
        outputTimeUnit = "ns"
        advanced("jvmForks", 3)
    }
}

val downloadLimine = tasks.register<DownloadFileTask>("downloadLimine") {
    group = "build"
    description = "Downloads the official prebuilt Limine release."
    sourceUrl.set(config.paths.liminePrebuilt.url)
    destinationFile.set(config.paths.liminePrebuilt.file)
}

val downloadLimineHeader = tasks.register<DownloadFileTask>("downloadLimineHeader") {
    group = "build"
    description = "Downloads the Limine protocol header matching the bootloader release."
    sourceUrl.set(config.paths.limineProtocolHeader.url)
    destinationFile.set(config.paths.limineProtocolHeader.file)
}

val downloadFreestndHeaders = tasks.register<DownloadFileTask>("downloadFreestndHeaders") {
    group = "build"
    description = "Downloads freestanding C headers."
    sourceUrl.set(config.paths.freestandingArchive.url)
    destinationFile.set(config.paths.freestandingArchive.file)
}

val userBenchmark = kotlin.targets.getByName<KotlinNativeTarget>("native")
    .binaries.getExecutable("qemuBenchmark", NativeBuildType.RELEASE)

val benchmarkService = file("src/qemuBenchmark/resources/benchmark.service")

val prepareUserland = tasks.register<Exec>("prepareUserland") {
    group = "build"
    description = "Builds a zstd-compressed CachyOS EROFS root filesystem."

    inputs.property("image", config.userland.image)
    inputs.property("platform", config.userland.platform)
    inputs.file(config.userland.script).withPathSensitivity(PathSensitivity.NONE)
    inputs.file(config.paths.initScript).withPathSensitivity(PathSensitivity.NONE)
    dependsOn(userBenchmark.linkTaskProvider)
    inputs.file(userBenchmark.outputFile).withPathSensitivity(PathSensitivity.NONE)
    inputs.file(benchmarkService).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(config.userland.archive)

    commandLine(
        "podman",
        "run", "--rm", "--pull=newer",
        "--platform", config.userland.platform,
        "--volume", "${config.userland.archive.parentFile.absolutePath}:/output:rw,Z",
        "--volume", "${config.userland.script.absolutePath}:/usr/local/bin/cpos-userland:ro,Z",
        "--volume", "${config.paths.initScript.absolutePath}:/usr/local/share/cpos/init:ro,Z",
        "--volume", "${userBenchmark.outputFile.absolutePath}:/usr/local/share/cpos/benchmark.kexe:ro,Z",
        "--volume", "${benchmarkService.absolutePath}:/usr/local/share/cpos/benchmark.service:ro,Z",
        config.userland.image,
        "/usr/local/bin/cpos-userland",
        config.userland.name,
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
    description = "Extracts official prebuilt Limine boot assets and its matching protocol header."
    dependsOn(downloadLimine, downloadLimineHeader)

    into(config.paths.limine)
    from({
        tarTree(resources.gzip(config.paths.liminePrebuilt.file))
    }) {
        include("*/limine-uefi-cd.bin", "*/BOOTX64.EFI")
        eachFile { path = "boot/$name" }
        includeEmptyDirs = false
    }
    from(config.paths.limineProtocolHeader.file) {
        into("include")
        rename { "limine.h" }
    }
}

compileRuntimeCallbacks.configure { dependsOn(prepareFreestndHeaders) }

val compileVdso = tasks.register<Exec>("compileVdso") {
    group = "build"
    description = "Compiles the userspace vDSO."
    inputs.file(config.vdso.source)
    inputs.file(config.paths.kernelC.resolve("vdso.h"))
    inputs.property("compileCommand", config.vdso.compileCommand)
    outputs.file(config.vdso.objectFile)
    commandLine(config.vdso.compileCommand)
}

val linkVdso = tasks.register<Exec>("linkVdso") {
    group = "build"
    description = "Links the userspace vDSO ELF image."
    dependsOn(compileVdso)
    inputs.file(config.vdso.objectFile)
    inputs.file(config.vdso.linkerScript)
    outputs.file(config.vdso.linkedImage)
    commandLine(config.vdso.linkCommand)
}

val stripVdso = tasks.register<Exec>("stripVdso") {
    group = "build"
    description = "Removes link-time metadata from the vDSO image."
    dependsOn(linkVdso)
    inputs.file(config.vdso.linkedImage)
    outputs.file(config.vdso.image)
    commandLine(config.vdso.stripCommand)
}

val embedVdso = tasks.register<Exec>("embedVdso") {
    group = "build"
    description = "Embeds the vDSO image into the kernel link."
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

            check(run(listOf("meson", "subprojects", "update", "--reset", "--sourcedir", source.path))) {
                "Failed to update mlibc subprojects"
            }

            val meson = listOf(
                "meson", "setup", source.path,
                "--cross-file", crossFile.path,
                "--buildtype=$buildType",
                "--prefix=${prefix.path}",
                "-Ddefault_library=static",
                "-Db_staticpic=false",
                "-Dlibgcc_dependency=false",
                "-Duse_freestnd_hdrs=enabled",
            )
            check(run(meson)) { "meson setup failed" }
            check(run(listOf("ninja", "-v"))) { "ninja build failed" }
            check(run(listOf("ninja", "install"))) { "ninja install failed" }
        }
    }
}

generateRuntimeLayout.configure { dependsOn(buildMlibc) }

tasks.matching { it.name == "cinteropBridgeNative" }.configureEach {
    dependsOn(prepareLimine, prepareFreestndHeaders, buildMlibc)
}

val compileC = tasks.register("compileC") {
    group = "build"
    description = "Compiles native sources into object files."
    dependsOn(prepareLimine, prepareFreestndHeaders, buildMlibc, generateRuntimeLayout)
    notCompatibleWithConfigurationCache("Runs an external compiler.")

    inputs.property("compiler", config.tools.cc)
    inputs.property("compileArgs", config.kernel.compileArgs)
    inputs.files(config.kernel.sources)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        config.paths.kernelC.resolve("bridge.h"),
        config.paths.kernelC.resolve("native.h"),
        config.paths.kernelC.resolve("context.h"),
        config.paths.kernelC.resolve("os_terminal.h"),
        config.paths.kernelC.resolve("vdso.h"),
        config.paths.limineHeader,
        config.paths.mlibcSyscallHeader,
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(config.paths.freestandingInclude)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(runtimeLayout)
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

val kernelLinks = kotlinBitcodeTasks.mapValues { (variant, bitcode) ->
    val output = if (variant == "main") config.paths.kernelElf else config.paths.root.resolve("$variant/kernel.elf")
    val name = if (variant == "main") "linkKernel" else "link${variant.replaceFirstChar(Char::uppercaseChar)}Kernel"
    tasks.register<Exec>(name) {
        group = "build"
        description = "Links the kernel and runtime libraries into an ELF executable."
        dependsOn(bitcode, compileC, embedVdso, buildMlibc)
        val kotlinObject = bitcode.flatMap { it.destinationFile }
        inputs.files(
            config.kernel.objects, config.vdso.blob,
            config.kernel.staticLibraries, config.kernel.runtimeLibraries,
        )
        inputs.file(kotlinObject)
        inputs.file(config.paths.linkerScript)
        outputs.file(output)
        commandLine(
            listOf(config.tools.linker) + config.kernel.linkArgs + listOf("-o", output.absolutePath) +
                config.kernel.objects.map(File::getAbsolutePath) + config.vdso.blob.absolutePath +
                kotlinObject.get().asFile.absolutePath + "--start-group" +
                config.kernel.staticLibraries.map(File::getAbsolutePath) +
                config.kernel.runtimeLibraries.map(File::getAbsolutePath) + "--end-group",
        )
    }
}
val linkKernel = kernelLinks.getValue("main")

tasks.named("assemble") {
    setDependsOn(listOf(linkKernel))
}

val compressKernel = tasks.register<GzipFileTask>("compressKernel") {
    group = "build"
    description = "Compresses the kernel with the highest gzip compression level."
    dependsOn(linkKernel)
    sourceFile.set(config.paths.kernelElf)
    destinationFile.set(config.paths.kernelGzip)
}

val bootConfigs = listOf("main", "qemuTest", "qemuBenchmark").associateWith { variant ->
    val title = variant.replaceFirstChar(Char::uppercaseChar)
    tasks.register<Copy>("configure$title") {
        val test = variant == "qemuTest"
        val console = if (variant == "main") config.console else "ttyS0,115200n8"
        val arguments = when (variant) {
            "qemuBenchmark" -> listOf("cpos.benchmark")
            "qemuTest" -> {
                val filter = providers.gradleProperty("qemuFilter").orElse(".*").get()
                require(filter.none { it == '\n' || it == '\r' || it == '\u0000' })
                val quoted = filter.replace("\\", "\\\\").replace("\"", "\\\"")
                listOf("verify.filter=\"$quoted\"")
            }
            else -> emptyList()
        }
        val commandLine = buildList {
            add("console=$console")
            if (!test) addAll(listOf("rootfs=${config.userland.name}", "rdinit=/init"))
            addAll(arguments)
        }.joinToString(" ")
        val tokens = mapOf(
            "kernel" to if (test) "boot():/boot/kernel.elf" else "\$boot():/boot/kernel.elf.gz",
            "rootfs" to if (test) "" else config.userland.name,
            "commandLine" to commandLine,
        )
        inputs.property("tokens", tokens)
        from(config.paths.assets.resolve("limine.conf"))
        into(config.paths.root.resolve("$variant/config"))
        expand(tokens)
    }
}

val stageIso = tasks.register<Sync>("stageIso") {
    group = "build"
    description = "Stages the compressed kernel, EROFS root filesystem, and Limine assets."
    dependsOn(compressKernel, prepareLimine, prepareUserland)

    into(config.paths.iso)
    from(bootConfigs.getValue("main")) { into("limine") }
    from(config.paths.limineUefi) { into("limine") }
    from(config.paths.limineEfi) { into("EFI/BOOT") }
    from(listOf(config.userland.archive, config.paths.kernelGzip)) { into("boot") }
}

val buildIso = tasks.register<Exec>("buildIso") {
    group = "build"
    description = "Builds the UEFI ISO image from staged assets."
    dependsOn(stageIso)

    inputs.dir(config.paths.iso)
    outputs.file(config.paths.isoImage)

    commandLine(
        config.tools.xorriso,
        "-as", "mkisofs",
        "--efi-boot", "limine/limine-uefi-cd.bin",
        "-efi-boot-part", "--efi-boot-image",
        config.paths.iso.absolutePath,
        "-o", config.paths.isoImage.absolutePath,
    )
}

tasks.register<Exec>("run") {
    group = "build"
    description = "Runs CoolPotOS in QEMU with serial on stdio."
    dependsOn(buildIso)

    commandLine(listOf("taskset", "--cpu-list", config.qemu.cpuSet, config.qemu.executable) + config.qemu.flags)
    standardInput = System.`in`
}

for (variant in listOf("qemuTest", "qemuBenchmark")) {
    val title = variant.replaceFirstChar(Char::uppercaseChar)
    val directory = config.paths.root.resolve(variant)
    val image = directory.resolve("kernel.iso")
    val staging = directory.resolve("iso")
    val benchmark = variant == "qemuBenchmark"
    val stage = tasks.register<Sync>("stage$title") {
        into(staging)
        from(bootConfigs.getValue(variant)) { into("limine") }
        if (benchmark) {
            dependsOn(stageIso)
            from(config.paths.iso) { exclude("limine/limine.conf") }
        } else {
            dependsOn(kernelLinks.getValue(variant), prepareLimine)
            from(directory.resolve("kernel.elf")) { into("boot") }
            from(config.paths.limineUefi) { into("limine") }
            from(config.paths.limineEfi) { into("EFI/BOOT") }
        }
    }
    val buildImage = tasks.register<Exec>("build${title}Iso") {
        dependsOn(stage)
        inputs.dir(staging)
        outputs.file(image)
        commandLine(
            config.tools.xorriso, "-as", "mkisofs", "--efi-boot", "limine/limine-uefi-cd.bin",
            "-efi-boot-part", "--efi-boot-image", staging.absolutePath, "-o", image.absolutePath,
        )
    }
    tasks.register<JavaExec>(variant) {
        group = "verification"
        description = "Runs $variant in the kernel and records its results."
        dependsOn(buildImage)
        val acceleration = providers.gradleProperty("qemuAcceleration").orElse("kvm").get()
        val cpu = providers.gradleProperty("qemuCpu").orElse(if (acceleration == "kvm") "host" else "max").get()
        val flags = if (benchmark) config.qemu.flags.mapIndexed { index, argument ->
            if (index > 0 && config.qemu.flags[index - 1] == "-display") "none"
            else argument.replace(config.paths.isoImage.absolutePath, image.absolutePath)
        } + listOf("-monitor", "none") else listOf(
            "-machine", "q35,accel=$acceleration", "-cpu", cpu,
            "-m", providers.gradleProperty("qemuMemory").orElse("2g").get(),
            "-smp", providers.gradleProperty("qemuSmp").orElse("4").get(),
            "-display", "none", "-monitor", "none", "-serial", "stdio", "-no-reboot",
            "-device", "isa-debug-exit,iobase=0xf4,iosize=0x04",
            "-drive", "if=pflash,format=raw,readonly=on,file=${config.paths.assets.resolve("ovmf-code.fd")}",
            "-drive", "file=${image.absolutePath},format=raw,snapshot=on",
        )
        val host = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("qemuHost")
        dependsOn(host.compileAllTaskName)
        classpath(host.output.allOutputs, host.runtimeDependencyFiles)
        mainClass.set("QemuRunnerKt")
        val bootCount = if (benchmark) providers.gradleProperty("benchmarkBoots").orElse("3").get() else "1"
        val kernelFile = if (benchmark) config.paths.kernelElf else directory.resolve("kernel.elf")
        args(
            "--mode", if (benchmark) "system" else "test",
            "--boots", bootCount,
            "--output", directory.resolve("results"),
            "--timeout", providers.gradleProperty("qemuTimeout").orElse("600").get(),
            "--kernel", kernelFile,
            "--build-type", if (config.debug) "debug" else "release",
            "--gc", providers.gradleProperty("kotlin.native.binary.gc").get(),
        )
        if (benchmark) args("--rootfs", config.userland.archive)
        args(listOf("--", config.tools.qemu) + flags)
    }
}

tasks.named("nativeTest") { enabled = false }
tasks.matching { it.name == "linkDebugTestNative" }.configureEach { enabled = false }
tasks.named("check") { setDependsOn(listOf(tasks.named("jvmTest"))) }

tasks.named<Delete>("clean") {
    description = "Deletes kernel build artifacts except mlibc."
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
