package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val JDK_VERSION = 21
private const val MAX_TEST_FORKS = 8
private const val TEST_FORK_CPU_DIVISOR = 2
private const val TEST_MAX_HEAP = "2g"

private val HARNESS_ENVIRONMENT_GATES =
  listOf(
    "SKILL_BILL_REAL_STORE_DB",
    "SKILL_BILL_MIGRATION_FIXTURE_DB",
  )

internal fun Project.configureKotlinJvm() {
  extensions.configure(KotlinJvmProjectExtension::class.java) {
    jvmToolchain(JDK_VERSION)
  }

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(JDK_VERSION))
    }
    withSourcesJar()
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_21)
      allWarningsAsErrors.set(true)
      freeCompilerArgs.add("-Xjsr305=strict")
      freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
  }

  configureKotlinJvmTestDefaults()
}

private fun Project.configureKotlinJvmTestDefaults() {
  tasks.withType(Test::class.java).configureEach {
    useJUnitPlatform()
    maxParallelForks =
      (Runtime.getRuntime().availableProcessors() / TEST_FORK_CPU_DIVISOR).coerceIn(1, MAX_TEST_FORKS)
    maxHeapSize = TEST_MAX_HEAP
    environment.remove("CLAUDE_CONFIG_DIR")
    environment.remove("CODEX_HOME")
    HARNESS_ENVIRONMENT_GATES.forEach { gate ->
      val value = providers.environmentVariable(gate)
      inputs.property(gate, value).optional(true)
      if (value.isPresent) {
        environment(gate, value.get())
      }
    }
    if (project.hasProperty("update-snapshots")) {
      systemProperty("update-snapshots", "true")
    }
    testLogging {
      events("skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
      quiet {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
      }
      error {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
      }
    }
  }
}
