package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

private const val JDK_VERSION = 21
private const val MAX_TEST_FORKS = 8
private const val TEST_FORK_CPU_DIVISOR = 2
private const val TEST_MAX_HEAP = "2g"
private const val TASK_TEMP_KEY_WIDTH = 8
private const val RUN_SCRATCH_DIR_KEY = "SKILL_BILL_RUN_SCRATCH_DIR"
private const val LOCK_DIR_KEY = "SKILL_BILL_LOCK_DIR"

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
    isolateJvmTempDir()
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

private fun Test.isolateJvmTempDir() {
  val taskKey =
    "${project.rootDir.absolutePath}$path".hashCode().toUInt().toString(radix = 16).padStart(TASK_TEMP_KEY_WIDTH, '0')
  val gradleUserHome = project.gradle.gradleUserHomeDir
  jvmArgumentProviders.add(JvmTempDirArgument(gradleUserHome, taskKey))
  doFirst {
    val tempDir = resolveTestTempDir(System.getenv(RUN_SCRATCH_DIR_KEY), gradleUserHome, taskKey)
    if (tempDir.parentFile == gradleUserHome) {
      sweepStaleTestTempDirs(gradleUserHome, System.currentTimeMillis())
    }
    tempDir.deleteRecursively()
    tempDir.mkdirs()
    (this as Test).environment(LOCK_DIR_KEY, tempDir.resolve("locks").absolutePath)
  }
  doLast {
    resolveTestTempDir(System.getenv(RUN_SCRATCH_DIR_KEY), gradleUserHome, taskKey).deleteRecursively()
  }
}

internal fun resolveTestTempDir(
  runScratch: String?,
  gradleUserHome: File,
  taskKey: String,
): File {
  val scratchRoot = runScratch?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isDirectory)
  return if (scratchRoot != null) {
    File(scratchRoot, "$RUN_SCRATCH_TEST_TEMP_PREFIX$taskKey")
  } else {
    File(gradleUserHome, "$TEST_TEMP_PREFIX$taskKey")
  }
}

internal fun sweepStaleTestTempDirs(
  root: File,
  nowMillis: Long,
) {
  root
    .listFiles { file ->
      file.isDirectory &&
        file.name.startsWith(TEST_TEMP_PREFIX) &&
        nowMillis - file.lastModified() > STALE_TEST_TEMP_MILLIS
    }?.forEach(File::deleteRecursively)
}

internal const val TEST_TEMP_PREFIX = "sbt-"
internal const val RUN_SCRATCH_TEST_TEMP_PREFIX = "t-"
internal const val STALE_TEST_TEMP_MILLIS = 24L * 60L * 60L * 1000L

private class JvmTempDirArgument(
  @get:Internal val gradleUserHome: File,
  @get:Internal val taskKey: String,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val tempDir = resolveTestTempDir(System.getenv(RUN_SCRATCH_DIR_KEY), gradleUserHome, taskKey)
    return listOf("-Djava.io.tmpdir=${tempDir.absolutePath}")
  }
}
