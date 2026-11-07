package dev.skillbill.desktop.buildlogic

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TestTempDirTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `test workers write temp files under the Gradle user home`() {
    val gradleHome = directory.resolve("gradle-home").apply { mkdirs() }
    val project =
      ProjectBuilder
        .builder()
        .withProjectDir(directory.resolve("checkout").apply { mkdirs() })
        .withGradleUserHomeDir(gradleHome)
        .build()
    val testTask = project.tasks.register("probe", GradleTest::class.java).get()

    testTask.isolateJvmTempDir()

    val tempDir =
      File(
        testTask.jvmArgumentProviders
          .flatMap { it.asArguments() }
          .single { it.startsWith("-Djava.io.tmpdir=") }
          .removePrefix("-Djava.io.tmpdir="),
      )
    assertEquals(gradleHome.canonicalFile, tempDir.parentFile.canonicalFile)
    assertTrue(tempDir.name.startsWith(TEST_TEMP_PREFIX))
  }

  @Test
  fun `test workers use a directory inside run scratch only when that directory exists`() {
    val gradleHome = directory.resolve("gradle-home")
    val scratch = directory.resolve("scratch").apply { mkdirs() }

    assertEquals(scratch.resolve("t-key"), resolveTestTempDir(scratch.path, gradleHome, "key"))
    assertEquals(
      gradleHome.resolve("sbt-key"),
      resolveTestTempDir(directory.resolve("gone").path, gradleHome, "key"),
    )
    assertEquals(gradleHome.resolve("sbt-key"), resolveTestTempDir(" ", gradleHome, "key"))
    assertEquals(gradleHome.resolve("sbt-key"), resolveTestTempDir(null, gradleHome, "key"))
  }

  @Test
  fun `sweep removes only stale test temp dirs from the Gradle user home`() {
    val now = STALE_TEST_TEMP_MILLIS * 2
    val stale = directory.resolve("${TEST_TEMP_PREFIX}stale").apply { mkdirs() }
    stale.resolve("leftover").writeText("x")
    stale.setLastModified(now - STALE_TEST_TEMP_MILLIS - 1)
    val recent = directory.resolve("${TEST_TEMP_PREFIX}recent").apply { mkdirs() }
    recent.setLastModified(now)
    val caches = directory.resolve("caches").apply { mkdirs() }
    caches.setLastModified(0)

    sweepStaleTestTempDirs(directory, now)

    assertFalse(stale.exists())
    assertTrue(recent.exists())
    assertTrue(caches.exists())
  }
}
