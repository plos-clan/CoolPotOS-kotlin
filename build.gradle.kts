plugins {
    base
}

tasks.named("assemble") {
    dependsOn(":kernel:assemble")
}

tasks.named("check") {
    dependsOn(":kernel:check")
}

tasks.named("build") {
    dependsOn(":kernel:build")
}

tasks.named("clean") {
    dependsOn(":kernel:clean")
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
