import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "org.plos_clan"
version = "0.0.1"

repositories {
    mavenCentral()
}

val projectName = "CoolPotOS"
val buildDir = layout.buildDirectory.get().asFile
val isoDir = File(buildDir, "iso")

val konanHome = System.getenv("KONAN_HOME") ?: "${System.getProperty("user.home")}/.konan"
val toolRoot = "$konanHome/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
val mlibcPrefix = File(buildDir, "mlibc-x86_64/prefix")

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

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "kernelMain"
                linkerOpts(
                    "-nostdlib",
                    "-z", "max-page-size=0x1000",
                    "--gc-sections",
                    "-T", project.file("assets/linker.ld").absolutePath
                )
            }
        }
    }

    sourceSets {
        val nativeMain by getting {
            kotlin.srcDir("kernel/kotlin")
            dependencies {
                implementation(libs.kotlinxSerializationJson)
            }
        }
    }

    targets.configureEach {
        if (this is KotlinNativeTarget) {
            compilations.getByName("main").cinterops {
                val limine by creating {
                    defFile(project.file("kernel/c/limine.def"))
                    packageName("natives")
                    includeDirs(project.file("kernel/c"))
                }
            }
        }
    }
}

tasks.register<Exec>("compileC") {
    group = "build"
    description = "Compile C sources"

    doLast {
        val cSources = listOf(
            "kernel/c/boot.c",
            "kernel/c/shim.c",
            "kernel/c/gdt.c"
        )

        val outputDir = File(buildDir, "c-objects")
        outputDir.mkdirs()

        cSources.forEach { source ->
            val outputFile = File(outputDir, "${File(source).nameWithoutExtension}.o")

            try {
                commandLine(
                    "clang",
                    "-target", "x86_64-freestanding",
                    "-std=c23", "-ffreestanding", "-nostdinc", "-fno-builtin",
                    "-m64", "-mno-red-zone", "-mcmodel=kernel", "-fno-stack-protector",
                    "-mno-80387", "-mno-mmx", "-mno-sse", "-mno-sse2",
                    "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                    "-Ikernel/c", "-O2",
                    "-c", project.file(source).absolutePath,
                    "-o", outputFile.absolutePath
                )

                println("Compiled $source -> ${outputFile.name}")
            } catch (e: Exception) {
                println("Failed to compile $source with clang")
                println("Error: ${e.message}")
                println("Make sure clang is installed and in PATH")
                throw e
            }
        }
    }
}

tasks.register<Exec>("linkKernel") {
    group = "build"
    description = "Link kernel ELF"
    dependsOn("compileKotlinNative", "compileC")

    doLast {
        val kernelElf = File(buildDir, "kernel.elf")
        val cObjectsDir = File(buildDir, "c-objects")
        val kotlinLib = File(buildDir, "bin/native/debugExecutable/CoolPotOS-Kotlin.kexe")

        commandLine(
            "ld.lld",
            "-m", "elf_x86_64",
            "-nostdlib",
            "-z", "max-page-size=0x1000",
            "--gc-sections",
            "-T", project.file("assets/linker.ld").absolutePath,
            "-o", kernelElf.absolutePath,
            cObjectsDir.listFiles { _, name -> name.endsWith(".o") }?.joinToString(" ") ?: "",
            kotlinLib.absolutePath
        )
        println("Kernel linked: ${kernelElf.absolutePath}")
    }
}

tasks.register<Exec>("buildIso") {
    group = "distribution"
    description = "Build ISO image"
    dependsOn("linkKernel")

    doLast {
        val kernelElf = File(buildDir, "kernel.elf")
        val isoImage = File(buildDir, "$projectName.iso")

        isoDir.mkdirs()
        File(isoDir, "limine").mkdirs()

        // 复制文件
        copy {
            from("assets/limine")
            into(File(isoDir, "limine"))
        }
        copy {
            from(kernelElf)
            into(isoDir)
            rename { "kernel.elf" }
        }

        commandLine(
            "xorriso", "-as", "mkisofs",
            "--efi-boot", "limine/limine-uefi-cd.bin",
            "-efi-boot-part", "--efi-boot-image",
            "-o", isoImage.absolutePath,
            isoDir.absolutePath
        )
        println("ISO created: ${isoImage.absolutePath}")
    }
}

tasks.register<Exec>("run") {
    group = "application"
    description = "Run in QEMU"
    dependsOn("buildIso")

    doLast {
        val isoImage = File(buildDir, "$projectName.iso")
        commandLine(
            "qemu-system-x86_64",
            "-m", "2G",
            "-M", "q35",
            "-cpu", "qemu64,+x2apic",
            "-drive", "if=pflash,format=raw,readonly=on,file=assets/ovmf-code.fd",
            "-serial", "stdio",
            isoImage.absolutePath
        )
    }
}

tasks.register("dev") {
    group = "development"
    description = "Build and run for development"
    dependsOn("run")
}

tasks.register<Delete>("cleanAll") {
    group = "build"
    delete(buildDir)
}

tasks.named("build") {
    dependsOn("linkKernel")
}

tasks.register<Exec>("buildMlibc") {
    group = "build setup"
    description = "Build mlibc library"

    inputs.file("build-mlibc")
    inputs.file("assets/mlibc.patch")
    outputs.dir(mlibcPrefix)

    doLast {
        if (!mlibcPrefix.exists()) {
            environment(
                "ARCH" to "x86_64",
                "CC" to "clang -target x86_64-unknown-none",
                "CXX" to "clang++ -target x86_64-unknown-none",
                "CFLAGS" to "-g -O2 -pipe -Wall -Wextra -nostdinc -ffreestanding -fno-stack-protector -fno-stack-check -fno-lto -fno-PIC -ffunction-sections -fdata-sections -m64 -march=x86-64 -mno-red-zone -mcmodel=kernel -D__thread='' -D_Thread_local='' -D_GNU_SOURCE",
                "CXXFLAGS" to "-g -O2 -pipe -Wall -Wextra -nostdinc -ffreestanding -fno-stack-protector -fno-stack-check -fno-lto -fno-PIC -ffunction-sections -fdata-sections -m64 -march=x86-64 -mno-red-zone -mcmodel=kernel -D__thread='' -D_Thread_local='' -D_GNU_SOURCE -fno-rtti -fno-exceptions -fno-sized-deallocation"
            )
            commandLine("./build-mlibc")
        }
    }
}

tasks.named("linkKernel") {
    dependsOn("buildMlibc")
}
