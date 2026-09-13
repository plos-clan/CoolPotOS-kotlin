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
    "buildIso",
    "buildMlibc",
    "buildSima",
    "compileC",
    "linkKernel",
    "nativeTest",
    "prepareUserland",
    "run",
).forEach { taskName ->
    tasks.register(taskName) {
        group = if (taskName == "nativeTest") "verification" else "build"
        description = "Delegates to :kernel:$taskName."
        dependsOn(":kernel:$taskName")
    }
}
