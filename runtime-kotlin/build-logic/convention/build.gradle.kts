plugins {
  `kotlin-dsl`
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
}

group = "dev.skillbill.runtime.buildlogic"

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.spotless.gradle.plugin)
  implementation(libs.detekt.gradle.plugin)
  implementation(libs.beryx.runtime.gradle.plugin)

  testImplementation(libs.junit.jupiter)
  testImplementation(gradleTestKit())

  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  val taskKey = "${rootDir.absolutePath}$path".hashCode().toUInt().toString(radix = 16).padStart(8, '0')
  val fallbackTempDir = temporaryDir
  doFirst {
    val runScratch =
      System.getenv("SKILL_BILL_RUN_SCRATCH_DIR")?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isDirectory)
    val tempDir = runScratch?.resolve("t-$taskKey") ?: fallbackTempDir
    tempDir.mkdirs()
    (this as Test).jvmArgs("-Djava.io.tmpdir=${tempDir.absolutePath}")
    environment.remove("SKILL_BILL_RUN_SCRATCH_DIR")
  }
}

val ccOptOutReason =
  "Kotlin 2.4.0-Beta2 test-compile is not configuration-cache compatible under Gradle 9.3 " +
    "(serializes the Kotlin daemon error-file/profile as a File where a Property is expected)."
tasks.named("compileTestKotlin") { notCompatibleWithConfigurationCache(ccOptOutReason) }
tasks.withType<Test>().configureEach { notCompatibleWithConfigurationCache(ccOptOutReason) }

spotless {
  kotlin {
    target("src/**/*.kt")
    ktlint()
    trimTrailingWhitespace()
    endWithNewline()
  }

  kotlinGradle {
    target("*.gradle.kts")
    ktlint()
    trimTrailingWhitespace()
    endWithNewline()
  }
}

detekt {
  config.setFrom(file("$rootDir/../config/detekt/detekt.yml"))
  buildUponDefaultConfig = true
  parallel = true
  basePath = rootDir.absolutePath
}

tasks {
  validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
  }
}

gradlePlugin {
  plugins {
    register("version") {
      id = "skillbill.version"
      implementationClass = "dev.skillbill.runtime.buildlogic.SkillBillVersionConventionPlugin"
    }
    register("jvmLibrary") {
      id = "skillbill.jvm-library"
      implementationClass = "dev.skillbill.runtime.buildlogic.JvmLibraryConventionPlugin"
    }
    register("repoTest") {
      id = "skillbill.repo-test"
      implementationClass = "dev.skillbill.runtime.buildlogic.RepoTestConventionPlugin"
    }
    register("quality") {
      id = "skillbill.quality"
      implementationClass = "dev.skillbill.runtime.buildlogic.QualityConventionPlugin"
    }
    register("runtimeImage") {
      id = "skillbill.runtime-image"
      implementationClass = "dev.skillbill.runtime.buildlogic.RuntimeImageConventionPlugin"
    }
    register("governedResources") {
      id = "skillbill.governed-resources"
      implementationClass = "dev.skillbill.runtime.buildlogic.GovernedResourcesConventionPlugin"
    }
  }
}
