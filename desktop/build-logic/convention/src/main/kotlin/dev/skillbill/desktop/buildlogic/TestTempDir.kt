package dev.skillbill.desktop.buildlogic

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

private const val TASK_TEMP_KEY_WIDTH = 8

internal const val TEST_TEMP_PREFIX = "sbt-"
internal const val STALE_TEST_TEMP_MILLIS = 24L * 60L * 60L * 1000L

internal fun Test.isolateJvmTempDir() {
  val taskKey =
    "${project.rootDir.absolutePath}$path".hashCode().toUInt().toString(radix = 16).padStart(TASK_TEMP_KEY_WIDTH, '0')
  val tempRoot = project.gradle.gradleUserHomeDir
  val tempDir = File(tempRoot, "$TEST_TEMP_PREFIX$taskKey")
  jvmArgumentProviders.add(JvmTempDirArgument(tempDir))
  doFirst {
    sweepStaleTestTempDirs(tempRoot, System.currentTimeMillis())
    tempDir.deleteRecursively()
    tempDir.mkdirs()
  }
  doLast {
    tempDir.deleteRecursively()
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
  @get:Internal val tempDir: File,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> = listOf("-Djava.io.tmpdir=${tempDir.absolutePath}")
}
