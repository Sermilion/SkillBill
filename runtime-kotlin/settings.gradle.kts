pluginManagement {
  includeBuild("build-logic")
  includeBuild("../build-identity")

  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "runtime-kotlin"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

include(
  "runtime-application",
  "runtime-contracts",
  "runtime-core",
  "runtime-domain",
  "runtime-engine",
  "runtime-infra",
  "runtime-infra:host",
  "runtime-infra:contracts",
  "runtime-infra:skills",
  "runtime-infra:launcher",
  "runtime-infra:workflow",
  "runtime-infra:http",
  "runtime-infra:sqlite",
  "runtime-cli",
  "runtime-mcp",
  "runtime-ports",
)
