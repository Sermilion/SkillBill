plugins {
  base
}

group = "dev.skillbill.tray"
version = "0.1.0"

tasks.named("check") {
  dependsOn(subprojects.map { "${it.path}:check" })
  dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
}

val composeAppTaskAliases = listOf(
  "run",
  "createDistributable",
  "packageDistributionForCurrentOS",
  "packageDmg",
  "packageMsi",
  "packageDeb",
  "packageRpm",
  "stageAppImage",
  "packageAppImage",
)
composeAppTaskAliases.forEach { name ->
  tasks.register(name) {
    group = "distribution"
    description = "Alias for :composeApp:$name."
    dependsOn(":composeApp:$name")
  }
}

tasks.register("test") {
  group = "verification"
  description = "Runs every module's jvmTest."
  dependsOn(subprojects.map { "${it.path}:jvmTest" })
}
