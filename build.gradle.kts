plugins {
    base
}

listOf("assemble", "check", "build", "clean").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(":kernel:$taskName")
    }
}

tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "Deletes all root and kernel build artifacts."
    dependsOn(":kernel:cleanAll")
    delete(layout.buildDirectory)
}

listOf(
    "jvmBenchmark",
    "buildImage",
    "buildMlibc",
    "compileC",
    "linkKernel",
    "jvmTest",
    "qemuTest",
    "qemuBenchmark",
    "prepareUserland",
    "run",
).forEach { taskName ->
    tasks.register(taskName) {
        group = if (taskName.endsWith("Test") || taskName.endsWith("Benchmark")) "verification" else "build"
        description = "Delegates to :kernel:$taskName."
        dependsOn(":kernel:$taskName")
    }
}
