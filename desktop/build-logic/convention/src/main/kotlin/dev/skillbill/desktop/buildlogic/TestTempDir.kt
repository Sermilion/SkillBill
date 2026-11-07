package dev.skillbill.desktop.buildlogic

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

private const val TASK_TEMP_KEY_WIDTH = 8
private const val RUN_SCRATCH_DIR_KEY = "SKILL_BILL_RUN_SCRATCH_DIR"
private const val LOCK_DIR_KEY = "SKILL_BILL_LOCK_DIR"

internal const val TEST_TEMP_PREFIX = "sbt-"
internal const val RUN_SCRATCH_TEST_TEMP_PREFIX = "t-"
internal const val STALE_TEST_TEMP_MILLIS = 24L * 60L * 60L * 1000L

internal fun Test.isolateJvmTempDir() {
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

private class JvmTempDirArgument(
  @get:Internal val gradleUserHome: File,
  @get:Internal val taskKey: String,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val tempDir = resolveTestTempDir(System.getenv(RUN_SCRATCH_DIR_KEY), gradleUserHome, taskKey)
    return listOf("-Djava.io.tmpdir=${tempDir.absolutePath}")
  }
}
