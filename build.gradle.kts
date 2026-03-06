import java.io.InputStream
import java.io.OutputStream
import java.net.URI

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

        val connection = URI.create(sourceUrl.get()).toURL().openConnection()
        connection.setRequestProperty("User-Agent", "Gradle")
        connection.getInputStream().use { input: InputStream ->
            target.outputStream().use { output: OutputStream ->
                input.copyTo(output)
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

val targetArch = "x86_64"
val projectName = "CoolPotOS"
val buildRootDir = layout.buildDirectory.get().asFile
val mlibcBuildDirName = "mlibc-$targetArch"

fun setting(propName: String, envName: String, defaultValue: String): String {
    val propValue = (findProperty(propName) as String?)?.takeIf(String::isNotBlank)
    val envValue = System.getenv(envName)?.takeIf(String::isNotBlank)
    return propValue ?: envValue ?: defaultValue
}

fun settingBoolean(propName: String, envName: String, defaultValue: Boolean): Boolean {
    val rawValue = (findProperty(propName) as String?)?.takeIf(String::isNotBlank)
        ?: System.getenv(envName)?.takeIf(String::isNotBlank)
        ?: return defaultValue
    return when (rawValue.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> throw GradleException("Expected boolean for $propName/$envName, got '$rawValue'.")
    }
}

val crossCc = setting("crossCc", "CROSS_CC", "clang")
val crossCxx = setting("crossCxx", "CROSS_CXX", "clang++")
val linker = setting("linker", "LINKER", "ld.lld")
val xorriso = setting("xorriso", "XORRISO", "xorriso")
val qemu = setting("qemu", "QEMU", "qemu-system-x86_64")
val debugMode = settingBoolean("debugMode", "DEBUG_MODE", false)

val isoDir = buildRootDir.resolve("iso")
val kernelCDir = file("kernel/c")
val kernelKotlinDir = file("kernel/kotlin")
val assetsDir = file("assets")
val limineConfigFile = assetsDir.resolve("limine.conf")

val linkerScript = assetsDir.resolve("linker.ld")
val bridgeDef = kernelCDir.resolve("bridge.def")
val mlibcPatch = assetsDir.resolve("mlibc.patch")

val limineRef = "v10.x-binary"
val limineArchiveUrl = "https://codeberg.org/Limine/Limine/archive/$limineRef.tar.gz"
val limineProtocolArchiveUrl = "https://codeberg.org/Limine/limine-protocol/archive/trunk.tar.gz"
val limineDir = buildRootDir.resolve("limine")
val downloadsDir = buildRootDir.resolve("downloads")
val limineArchive = downloadsDir.resolve("limine-$limineRef.tar.gz")
val limineProtocolArchive = downloadsDir.resolve("limine-protocol-trunk.tar.gz")
val limineIncludeDir = limineDir.resolve("include")
val limineBootDir = limineDir.resolve("boot")
val limineHeader = limineIncludeDir.resolve("limine.h")
val limineUefiCdBin = limineBootDir.resolve("limine-uefi-cd.bin")

val freestndHeadersRef = "trunk"
val freestndHeadersArchiveUrl = "https://codeberg.org/OSDev/freestnd-c-hdrs-0bsd/archive/$freestndHeadersRef.tar.gz"
val freestndHeadersDir = buildRootDir.resolve("freestnd-c-hdrs")
val freestndHeadersArchive = downloadsDir.resolve("freestnd-c-hdrs-0bsd-$freestndHeadersRef.tar.gz")
val freestndHeadersIncludeDir = freestndHeadersDir.resolve("include")

val konanHome = System.getenv("KONAN_HOME") ?: "${System.getProperty("user.home")}/.konan"
val defaultToolRoot = "$konanHome/dependencies/$targetArch-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
val toolRoot = setting("konanToolRoot", "KONAN_TOOLROOT", defaultToolRoot)

val defaultMlibcPrefix = buildRootDir.resolve("$mlibcBuildDirName/prefix").path
val mlibcPrefix = file(setting("mlibcPrefix", "MLIBC_PREFIX", defaultMlibcPrefix))
val konanGccLibDir = File(toolRoot, "lib/gcc/$targetArch-unknown-linux-gnu/8.3.0")
val konanSysrootLibDir = File(toolRoot, "$targetArch-unknown-linux-gnu/sysroot/lib")
val mlibcLibDir = mlibcPrefix.resolve("lib")

val cSourceNames = listOf("boot.c", "shim.c", "syscall.c", "gdt.c", "idt.c")
val cSources = cSourceNames.map(kernelCDir::resolve)

val cFlagsTarget = listOf("-target", "$targetArch-freestanding")
val cFlagsLanguage = listOf("-std=c23", "-ffreestanding", "-nostdinc", "-fno-builtin")
val cFlagsMachine = listOf("-m64", "-mno-red-zone", "-mcmodel=kernel", "-fno-stack-protector")
val cFlagsNoSimd = listOf("-mno-80387", "-mno-mmx", "-mno-sse", "-mno-sse2")
val cFlagsWarnings = listOf("-Wall", "-Wextra", "-Wpedantic", "-Werror")
val cFlagsIncludes = listOf(
    "-I${kernelCDir.path}",
    "-I${buildRootDir.path}",
    "-I${limineIncludeDir.path}",
    "-I${freestndHeadersIncludeDir.path}"
)
val cFlagsOptimization = listOf(if (debugMode) "-Og" else "-O2")
val cCompilerArgs =
    cFlagsTarget +
    cFlagsLanguage +
    cFlagsMachine +
    cFlagsNoSimd +
    cFlagsWarnings +
    cFlagsIncludes +
    cFlagsOptimization

val cObjectsDir = buildRootDir.resolve("c-objects")
val cObjectFiles = cSources.map { source -> cObjectsDir.resolve("${source.nameWithoutExtension}.o") }

val mlibcArtifacts = listOf("libc.a", "libm.a", "libpthread.a").map(mlibcLibDir::resolve)
val kotlinStaticLib = buildRootDir.resolve("bin/native/debugStatic/libkernel.a")
val runtimeLibs = buildList {
    addAll(mlibcArtifacts)
    add(konanSysrootLibDir.resolve("libstdc++.a"))
    addAll(listOf("libgcc.a", "libgcc_eh.a").map(konanGccLibDir::resolve))
}

val ldFlagsFormat = listOf("-m", "elf_$targetArch")
val ldFlagsRuntime = listOf("-nostdlib")
val ldFlagsPaging = listOf("-z", "max-page-size=0x1000")
val ldFlagsSections = listOf("--gc-sections")
val ldFlagsScript = listOf("-T", linkerScript.absolutePath)
val ldFlags =
    ldFlagsFormat +
    ldFlagsRuntime +
    ldFlagsPaging +
    ldFlagsSections +
    ldFlagsScript

val xorrisoFlagsMode = listOf("-as", "mkisofs")
val xorrisoFlagsBoot = listOf("--efi-boot", "limine/limine-uefi-cd.bin", "-efi-boot-part", "--efi-boot-image")
val xorrisoFlags = xorrisoFlagsMode + xorrisoFlagsBoot

val qemuMemory = setting("qemuMemory", "QEMU_MEMORY", "256m")
val qemuFlagsMachine = listOf("-m", qemuMemory, "-M", "q35", "-cpu", "qemu64,+x2apic", "-no-reboot")
val qemuFlagsFirmware = listOf("-drive", "if=pflash,format=raw,readonly=on,file=assets/ovmf-code.fd")
val qemuFlagsDebug = listOf("-s", "-S")
val qemuBaseFlags =
    qemuFlagsMachine +
    qemuFlagsFirmware +
    (if (debugMode) qemuFlagsDebug else emptyList())

val mlibcTarget = "$targetArch-unknown-none"
val mlibcCc = "$crossCc -target $mlibcTarget"
val mlibcCxx = "$crossCxx -target $mlibcTarget"
val mlibcCFlagsBase = listOf("-g", "-O2", "-pipe")
val mlibcCFlagsWarnings = listOf("-Wall", "-Wextra", "-nostdinc", "-ffreestanding")
val mlibcCFlagsSafety = listOf("-fno-stack-protector", "-fno-stack-check", "-fno-lto", "-fno-PIC")
val mlibcCFlagsSections = listOf("-ffunction-sections", "-fdata-sections")
val mlibcCFlagsArch = listOf("-m64", "-march=x86-64", "-mno-red-zone", "-mcmodel=kernel")
val mlibcCFlagsDefines = listOf("-D__thread=''", "-D_Thread_local=''", "-D_GNU_SOURCE")
val mlibcCxxOnlyFlags = listOf("-fno-rtti", "-fno-exceptions", "-fno-sized-deallocation")
val mlibcCFlagArgs =
    mlibcCFlagsBase +
    mlibcCFlagsWarnings +
    mlibcCFlagsSafety +
    mlibcCFlagsSections +
    mlibcCFlagsArch +
    mlibcCFlagsDefines
val mlibcCxxFlagArgs = mlibcCFlagArgs + mlibcCxxOnlyFlags
val mlibcCFlags = mlibcCFlagArgs.joinToString(" ")
val mlibcCxxFlags = mlibcCxxFlagArgs.joinToString(" ")
val linkInputs = cObjectFiles + kotlinStaticLib + runtimeLibs

fun Iterable<File>.absolutePaths(): List<String> = map(File::getAbsolutePath)

fun runProcess(
    command: List<String>,
    workingDir: File? = null,
    path: String? = null,
    quiet: Boolean = false
): Boolean {
    val process = ProcessBuilder(command).apply {
        workingDir?.let(::directory)
        path?.let { environment()["PATH"] = it }
        if (quiet) {
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectError(ProcessBuilder.Redirect.DISCARD)
        } else {
            inheritIO()
        }
    }.start()
    return process.waitFor() == 0
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")

    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Mac OS X" && !isArm64 -> macosX64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.binaries.staticLib {
        baseName = "kernel"
        if (debugMode) {
            freeCompilerArgs += "-g"
        }
    }

    sourceSets.named("nativeMain") {
        kotlin.srcDir(kernelKotlinDir)
    }

    nativeTarget.compilations.getByName("main").cinterops {
        create("bridge") {
            defFile(bridgeDef)
            packageName("bridge")
            includeDirs(kernelCDir, limineIncludeDir, freestndHeadersIncludeDir)
        }
    }
}

val kernelElf = buildRootDir.resolve("kernel.elf")
val isoImage = buildRootDir.resolve("$projectName.iso")

val downloadLimine by tasks.register<DownloadFileTask>("downloadLimine") {
    group = "build"
    description = "Downloads Limine bootloader assets."
    sourceUrl.set(limineArchiveUrl)
    destinationFile.set(limineArchive)
}

val downloadLimineProtocol by tasks.register<DownloadFileTask>("downloadLimineProtocol") {
    group = "build"
    description = "Downloads limine-protocol headers."
    sourceUrl.set(limineProtocolArchiveUrl)
    destinationFile.set(limineProtocolArchive)
}

val downloadFreestndHeaders by tasks.register<DownloadFileTask>("downloadFreestndHeaders") {
    group = "build"
    description = "Downloads freestanding C headers."
    sourceUrl.set(freestndHeadersArchiveUrl)
    destinationFile.set(freestndHeadersArchive)
}

val prepareFreestndHeaders by tasks.register<Sync>("prepareFreestndHeaders") {
    group = "build"
    description = "Extracts all freestanding C headers."
    dependsOn(downloadFreestndHeaders)

    into(freestndHeadersDir)
    from({
        tarTree(resources.gzip(freestndHeadersArchive))
    }) {
        include("*/include/**")
        eachFile {
            path = path.substringAfter('/')
        }
        includeEmptyDirs = false
    }
}

val prepareLimine by tasks.register<Sync>("prepareLimine") {
    group = "build"
    description = "Extracts Limine boot binary and protocol header."
    dependsOn(downloadLimine, downloadLimineProtocol)

    into(limineDir)
    from({
        tarTree(resources.gzip(limineArchive))
    }) {
        include("*/limine-uefi-cd.bin")
        eachFile {
            if (name == "limine-uefi-cd.bin") {
                path = "boot/limine-uefi-cd.bin"
            }
        }
        includeEmptyDirs = false
    }
    from({
        tarTree(resources.gzip(limineProtocolArchive))
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

val compileC by tasks.register("compileC") {
    group = "build"
    description = "Compiles C sources into object files."
    dependsOn(prepareLimine, prepareFreestndHeaders, buildMlibc)
    notCompatibleWithConfigurationCache("Uses ProcessBuilder from build script.")

    inputs.files(cSources)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(kernelCDir.resolve("bridge.h"), limineHeader)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(freestndHeadersIncludeDir)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(buildRootDir.resolve("syscall.h"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(cObjectsDir)

    doLast {
        cObjectsDir.mkdirs()
        cSources.forEach { source ->
            val output = cObjectsDir.resolve("${source.nameWithoutExtension}.o")
            val command = buildList {
                add(crossCc)
                addAll(cCompilerArgs)
                add("-c")
                add(source.absolutePath)
                add("-o")
                add(output.absolutePath)
            }
            check(runProcess(command)) {
                "Failed to compile ${source.name}"
            }
        }
    }
}

val buildMlibc by tasks.register<Exec>("buildMlibc") {
    group = "build"
    description = "Builds the mlibc C library."
    notCompatibleWithConfigurationCache("Uses ProcessBuilder from build script.")

    inputs.files(mlibcPatch)
    inputs.dir("mlibc")
    outputs.dir(mlibcPrefix)

    val mlibcBuildDir = buildRootDir.resolve(mlibcBuildDirName)
    val mlibcDir = file("mlibc")
    val mlibcSyscallH = mlibcDir.resolve("sysdeps/template/include/sys/syscall.h")
    val crossFile = mlibcBuildDir.resolve("cross_file.txt")
    val buildPath = "${mlibcBuildDir.absolutePath}:${System.getenv("PATH") ?: ""}"

    doFirst {
        delete(mlibcBuildDir)
        mlibcBuildDir.mkdirs()

        fun createWrapper(name: String, compiler: String, flags: String) =
            mlibcBuildDir.resolve(name).apply {
                writeText("#!/bin/sh\n$compiler $flags \"\$@\"\n")
                setExecutable(true)
            }
        createWrapper("cc", mlibcCc, mlibcCFlags)
        createWrapper("c++", mlibcCxx, mlibcCxxFlags)

        crossFile.writeText(
            """
            [binaries]
            c = 'cc'
            cpp = 'c++'

            [host_machine]
            system = 'template'
            cpu_family = '$targetArch'
            cpu = '$targetArch'
            endian = 'little'
            """.trimIndent()
        )

        val gitApplyCommand = listOf("git", "-C", mlibcDir.absolutePath, "apply")

        if (!runProcess(
            gitApplyCommand + mlibcPatch.absolutePath,
            workingDir = mlibcBuildDir,
            path = buildPath,
            quiet = true
        )) {
            val patchResult = runProcess(
                gitApplyCommand + listOf("-R", "--check", mlibcPatch.absolutePath),
                workingDir = mlibcBuildDir,
                path = buildPath,
                quiet = true
            )
            check(patchResult) { "Failed to apply ${mlibcPatch.name}" }
        }

        val syscallPath = buildRootDir.resolve("syscall.h").toPath()
        val fileSystemProvider = syscallPath.fileSystem.provider()
        fileSystemProvider.deleteIfExists(syscallPath)
        fileSystemProvider.createSymbolicLink(syscallPath, mlibcSyscallH.toPath())
    }

    workingDir(mlibcBuildDir)
    environment("PATH", buildPath)
    commandLine(
        "meson",
        "setup",
        mlibcDir.absolutePath,
        "--cross-file",
        crossFile.absolutePath,
        "--buildtype=debug",
        "--prefix=${mlibcBuildDir.absolutePath}/prefix",
        "-Ddefault_library=static",
        "-Dlibgcc_dependency=false",
        "-Duse_freestnd_hdrs=enabled"
    )

    doLast {
        fun runNinja(vararg args: String, errorMsg: String) {
            val ninjaResult = runProcess(
                args.toList(),
                workingDir = mlibcBuildDir,
                path = buildPath
            )
            check(ninjaResult) { errorMsg }
        }
        runNinja("ninja", "-v", errorMsg = "ninja build failed")
        runNinja("ninja", "install", errorMsg = "ninja install failed")
    }
}

val linkKernel by tasks.register<Exec>("linkKernel") {
    group = "build"
    description = "Links the kernel and runtime libraries into an ELF executable."
    dependsOn("linkDebugStaticNative", compileC, buildMlibc)

    inputs.files(linkInputs)
    inputs.file(linkerScript)
    outputs.file(kernelElf)

    val linkCommand = buildList {
        add(linker)
        addAll(ldFlags)
        add("-o")
        add(kernelElf.absolutePath)
        addAll(cObjectFiles.absolutePaths())
        add(kotlinStaticLib.absolutePath)
        add("--start-group")
        addAll(runtimeLibs.absolutePaths())
        add("--end-group")
    }
    commandLine(linkCommand)
}

tasks.named("build") {
    dependsOn(linkKernel)
}

val stageIso by tasks.register<Sync>("stageIso") {
    group = "build"
    description = "Stages the kernel and limine assets into the ISO directory."
    dependsOn(linkKernel, prepareLimine)

    into(isoDir)
    from(limineConfigFile) { into("limine") }
    from(limineUefiCdBin) { into("limine") }
    from(kernelElf)
}

val buildIso by tasks.register<Exec>("buildIso") {
    group = "build"
    description = "Builds the UEFI ISO image from staged assets."
    dependsOn(stageIso)

    inputs.dir(isoDir)
    outputs.file(isoImage)

    val isoCommand = buildList {
        add(xorriso)
        addAll(xorrisoFlags)
        add(isoDir.absolutePath)
        add("-o")
        add(isoImage.absolutePath)
    }
    commandLine(isoCommand)
}

tasks.register<Exec>("run") {
    group = "build"
    description = "Runs CoolPotOS in QEMU with serial on stdio."
    dependsOn(buildIso)

    val runCommand = buildList {
        add(qemu)
        addAll(qemuBaseFlags)
        add("-serial")
        add("stdio")
        add(isoImage.absolutePath)
    }
    commandLine(runCommand)
}

tasks.named<Delete>("clean") {
    description = "Deletes kernel build artifacts while preserving mlibc build outputs."
    setDelete(
        fileTree(buildRootDir) {
            exclude(mlibcBuildDirName, "$mlibcBuildDirName/**")
        }
    )
}

tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "Deletes all build artifacts, including mlibc."
    delete(buildRootDir)
}
