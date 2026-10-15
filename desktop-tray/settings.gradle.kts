rootProject.name = "skill-bill-desktop-tray"

includeBuild("../runtime-kotlin") {
  dependencySubstitution {
    substitute(module("dev.skillbill:runtime-core")).using(project(":runtime-core"))
    substitute(module("dev.skillbill:runtime-contracts")).using(project(":runtime-contracts"))
    substitute(module("dev.skillbill:runtime-infra-host")).using(project(":runtime-infra:host"))
    substitute(module("dev.skillbill:runtime-infra-contracts")).using(project(":runtime-infra:contracts"))
    substitute(module("dev.skillbill:runtime-infra-sqlite")).using(project(":runtime-infra:sqlite"))
  }
}

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx\\..*")
        includeGroupByRegex("com\\.android(\\..*)?")
        includeGroupByRegex("com\\.google\\.android\\..*")
      }
    }
    mavenCentral()
  }
}

include(
  ":composeApp",
  ":core:common", ":core:domain", ":core:designsystem", ":core:ui", ":core:testing", ":core:data", ":core:datastore",
  ":feature:work", ":feature:detail", ":feature:history", ":feature:settings",
)
