interface CompileCFileParameters : WorkParameters {
    val sourceFile: RegularFileProperty
    val outputFile: RegularFileProperty
    val commonArgs: ListProperty<String>
}

abstract class CompileCFileWork @Inject constructor(
    private val execOperations: ExecOperations
) : WorkAction<CompileCFileParameters> {
    override fun execute() {
        val source = parameters.sourceFile.asFile.get()
        val output = parameters.outputFile.asFile.get()

        execOperations.exec {
            commandLine(
                "clang",
                *parameters.commonArgs.get().toTypedArray(),
                "-c",
                source.absolutePath,
                "-o",
                output.absolutePath
            )
        }
    }
}

@DisableCachingByDefault(because = "Invokes external clang processes")
abstract class CompileCSourcesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val commonArgs: ListProperty<String>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @TaskAction
    fun compileAll() {
        val outputDir = outputDirectory.get().asFile.apply { mkdirs() }

        val queue = workerExecutor.noIsolation()
        sourceFiles.files.forEach { source ->
            queue.submit(CompileCFileWork::class.java) {
                sourceFile.set(source)
                outputFile.set(outputDir.resolve("${source.nameWithoutExtension}.o"))
                commonArgs.set(this@CompileCSourcesTask.commonArgs)
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "org.plos_clan"
version = "0.0.1"

repositories {
    mavenCentral()
}

val buildDir = layout.buildDirectory.get().asFile
val isoDir = File(buildDir, "iso")
val cDir = file("kernel/c")
val assetsDir = file("assets")
val linkerScript = assetsDir.resolve("linker.ld")
val bridgeDef = cDir.resolve("bridge.def")
val mlibcPatch = assetsDir.resolve("mlibc.patch")
val limineAssetsDir = assetsDir.resolve("limine")

val konanHome = System.getenv("KONAN_HOME") ?: "${System.getProperty("user.home")}/.konan"
val toolRoot = "$konanHome/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
val mlibcPrefix = File(buildDir, "mlibc-x86_64/prefix")
val konanGccLibDir = File(toolRoot, "lib/gcc/x86_64-unknown-linux-gnu/8.3.0")
val konanSysrootLibDir = File(toolRoot, "x86_64-unknown-linux-gnu/sysroot/lib")
val mlibcLibDir = File(mlibcPrefix, "lib")

val cSources = listOf("boot.c", "shim.c", "gdt.c", "idt.c").map(cDir::resolve)
val cCompilerArgs = listOf(
    "-target", "x86_64-freestanding",
    "-std=c23", "-ffreestanding", "-nostdinc", "-fno-builtin",
    "-m64", "-mno-red-zone", "-mcmodel=kernel", "-fno-stack-protector",
    "-mno-80387", "-mno-mmx", "-mno-sse", "-mno-sse2",
    "-Wall", "-Wextra", "-Wpedantic", "-Werror",
    "-I${cDir.path}", "-O2"
)
val cObjectsDir = File(buildDir, "c-objects")
val cObjectFiles = cSources.map { cObjectsDir.resolve("${it.nameWithoutExtension}.o") }
val mlibcArtifacts = listOf("libc.a", "libm.a", "libpthread.a").map(mlibcLibDir::resolve)
val kotlinStaticLib = File(buildDir, "bin/native/debugStatic/libkernel.a")
val runtimeLibs = mlibcArtifacts +
    konanSysrootLibDir.resolve("libstdc++.a") +
    listOf("libgcc.a", "libgcc_eh.a").map(konanGccLibDir::resolve)
val freestandingFlags = listOf(
    "-g -O2 -pipe -Wall -Wextra -nostdinc -ffreestanding",
    "-fno-stack-protector -fno-stack-check -fno-lto -fno-PIC",
    "-ffunction-sections -fdata-sections -m64 -march=x86-64",
    "-mno-red-zone -mcmodel=kernel -D__thread='' -D_Thread_local='' -D_GNU_SOURCE"
).joinToString(" ")
val linkInputs = cObjectFiles + kotlinStaticLib + runtimeLibs

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

    nativeTarget.binaries.staticLib { baseName = "kernel" }

    sourceSets.named("nativeMain") {
        kotlin.srcDir("kernel/kotlin")
        dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
    }

    nativeTarget.compilations.getByName("main").cinterops {
        create("bridge") {
            defFile(bridgeDef)
            packageName("bridge")
            includeDirs(cDir)
        }
    }
}

val kernelElf = File(buildDir, "kernel.elf")
val isoImage = File(buildDir, "CoolPotOS.iso")

val compileC by tasks.register<CompileCSourcesTask>("compileC") {
    group = "build"
    description = "Compiles C sources into object files."

    sourceFiles.from(cSources)
    inputs.files(cDir.resolve("bridge.h"), cDir.resolve("limine.h"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputDirectory.set(cObjectsDir)
    commonArgs.set(cCompilerArgs)
}

val buildMlibc by tasks.register<Exec>("buildMlibc") {
    group = "build"
    description = "Builds the mlibc C library."
    inputs.files("build-mlibc", mlibcPatch)
    outputs.dir(mlibcPrefix)

    onlyIf { mlibcArtifacts.any { !it.exists() } }

    environment(
        mapOf(
            "ARCH" to "x86_64",
            "CC" to "clang -target x86_64-unknown-none",
            "CXX" to "clang++ -target x86_64-unknown-none",
            "CFLAGS" to freestandingFlags,
            "CXXFLAGS" to "$freestandingFlags -fno-rtti -fno-exceptions -fno-sized-deallocation"
        )
    )
    commandLine("./build-mlibc")
}

val linkKernel by tasks.register<Exec>("linkKernel") {
    group = "build"
    description = "Links the kernel and runtime libraries into an ELF executable."
    dependsOn("linkDebugStaticNative", compileC, buildMlibc)

    inputs.files(linkInputs)
    inputs.file(linkerScript)
    outputs.file(kernelElf)

    val linkCommand = listOf(
            "ld.lld",
            "-m", "elf_x86_64",
            "-nostdlib",
            "-z", "max-page-size=0x1000",
            "--gc-sections",
            "-T", linkerScript.absolutePath,
            "-o", kernelElf.absolutePath
        ) +
        cObjectFiles.map(File::getAbsolutePath) +
        kotlinStaticLib.absolutePath +
        "--start-group" +
        runtimeLibs.map(File::getAbsolutePath) +
        "--end-group"
    commandLine(linkCommand)
}

val stageIso by tasks.register<Sync>("stageIso") {
    group = "build"
    description = "Stages the kernel and limine assets into the ISO directory."
    dependsOn(linkKernel)

    into(isoDir)
    from(limineAssetsDir) {
        into("limine")
    }
    from(kernelElf)
}

val buildIso by tasks.register<Exec>("buildIso") {
    group = "build"
    description = "Stages the kernel and limine assets into an ISO image."
    dependsOn(stageIso)

    inputs.dir(isoDir)
    outputs.file(isoImage)

    commandLine(
        "xorriso", "-as", "mkisofs",
        "--efi-boot", "limine/limine-uefi-cd.bin",
        "-efi-boot-part", "--efi-boot-image",
        "-o", isoImage.absolutePath,
        isoDir.absolutePath
    )
}

val runTask by tasks.register("run") {
    group = "runCoolPotOS"
    description = "Runs CoolPotOS in QEMU."
    dependsOn(buildIso)

    doLast {
        val serialLog = File(buildDir, "qemu-serial.log")
        serialLog.parentFile.mkdirs()

        ProcessBuilder(
            "qemu-system-x86_64",
            "-daemonize",
            "-m", "2G",
            "-M", "q35",
            "-cpu", "qemu64,+x2apic",
            "-drive", "if=pflash,format=raw,readonly=on,file=assets/ovmf-code.fd",
            "-serial", "file:${serialLog.absolutePath}",
            isoImage.absolutePath
        )
            .directory(project.projectDir)
            .start()

        println("QEMU started in background.")
        println("Serial log: ${serialLog.absolutePath}")
    }
}

tasks.register("dev") {
    group = "runCoolPotOS"
    description = "Runs CoolPotOS in QEMU."
    dependsOn(runTask)
}

tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "Deletes all build artifacts."
    delete(buildDir)
}

tasks.named("build") {
    dependsOn(linkKernel)
}
