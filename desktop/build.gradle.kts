plugins {
  base
  id("skillbill.build-identity") apply false
}

group = "dev.skillbill.desktop"
version = "0.1.0"

// The implicit ":core" parent has no build script, so it owns no check or jvmTest task.
val moduleProjects = subprojects.filter { it.buildFile.exists() }

tasks.named("check") {
  dependsOn(moduleProjects.map { "${it.path}:check" })
  dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
}

val appTaskAliases = listOf("run", "createDistributable")
appTaskAliases.forEach { name ->
  tasks.register(name) {
    group = "distribution"
    description = "Alias for :app:$name."
    dependsOn(":app:$name")
  }
}

tasks.register("test") {
  group = "verification"
  description = "Runs every module's jvmTest."
  dependsOn(moduleProjects.map { "${it.path}:jvmTest" })
}
