package dev.skillbill.runtime.buildlogic

import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.gradle.api.tasks.testing.Test as GradleTest

private const val MAX_TEMP_DIR_GROWTH = 16

class JvmLibraryConventionPluginTest {
  @Test
  fun `test workers cannot inherit live agent configuration roots`() {
    val project = ProjectBuilder.builder().build()
    val testTask = project.tasks.register("installProbe", GradleTest::class.java).get()
    testTask.environment("CODEX_HOME", "/operator/codex")
    testTask.environment("CLAUDE_CONFIG_DIR", "/operator/claude")
    testTask.environment("PATH", "/test/bin")

    project.pluginManager.apply(JvmLibraryConventionPlugin::class.java)

    assertFalse(testTask.environment.containsKey("CODEX_HOME"))
    assertFalse(testTask.environment.containsKey("CLAUDE_CONFIG_DIR"))
    assertEquals("/test/bin", testTask.environment["PATH"])
  }

  @Test
  fun `test workers write temp files on disk under a short directory outside the checkout`(
    @TempDir workspace: File,
  ) {
    val gradleHome = workspace.resolve("gradle-home").apply { mkdirs() }
    val project =
      ProjectBuilder
        .builder()
        .withProjectDir(workspace.resolve("checkout").apply { mkdirs() })
        .withGradleUserHomeDir(gradleHome)
        .build()
    val testTask = project.tasks.register("installProbe", GradleTest::class.java).get()

    project.pluginManager.apply(JvmLibraryConventionPlugin::class.java)

    val tmpdirArgs =
      testTask.jvmArgumentProviders.flatMap { it.asArguments() }.filter { it.startsWith("-Djava.io.tmpdir=") }
    assertEquals(1, tmpdirArgs.size, "Expected exactly one java.io.tmpdir override, got $tmpdirArgs")
    val tempDir = File(tmpdirArgs.single().removePrefix("-Djava.io.tmpdir="))
    assertEquals(gradleHome.canonicalFile, tempDir.parentFile.canonicalFile)
    assertTrue(tempDir.name.startsWith(TEST_TEMP_PREFIX), "Unexpected temp dir name ${tempDir.name}")
    assertTrue(
      tempDir.name.length <= MAX_TEMP_DIR_GROWTH,
      "Unix socket tests bind under java.io.tmpdir and macOS caps those paths at 104 bytes: $tempDir",
    )
    assertFalse(
      tempDir.startsWith(project.rootDir),
      "Test temp dirs must stay outside the checkout so non-repository paths remain available.",
    )
  }

  @Test
  fun `test workers use a directory inside run scratch only when that directory exists`(
    @TempDir workspace: File,
  ) {
    val gradleHome = workspace.resolve("gradle-home")
    val scratch = workspace.resolve("scratch").apply { mkdirs() }

    assertEquals(scratch.resolve("t-key"), resolveTestTempDir(scratch.path, gradleHome, "key"))
    assertEquals(
      gradleHome.resolve("sbt-key"),
      resolveTestTempDir(workspace.resolve("gone").path, gradleHome, "key"),
    )
    assertEquals(gradleHome.resolve("sbt-key"), resolveTestTempDir(" ", gradleHome, "key"))
    assertEquals(gradleHome.resolve("sbt-key"), resolveTestTempDir(null, gradleHome, "key"))
  }

  @Test
  fun `sweep removes only stale test temp dirs from the Gradle user home`(
    @TempDir gradleHome: File,
  ) {
    val now = STALE_TEST_TEMP_MILLIS * 2
    val stale = gradleHome.resolve("${TEST_TEMP_PREFIX}stale").apply { mkdirs() }
    stale.resolve("leftover").writeText("x")
    stale.setLastModified(now - STALE_TEST_TEMP_MILLIS - 1)
    val recent = gradleHome.resolve("${TEST_TEMP_PREFIX}recent").apply { mkdirs() }
    recent.setLastModified(now)
    val caches = gradleHome.resolve("caches").apply { mkdirs() }
    caches.setLastModified(0)

    sweepStaleTestTempDirs(gradleHome, now)

    assertFalse(stale.exists())
    assertTrue(recent.exists())
    assertTrue(caches.exists())
  }

  @Test
  fun `nested module jars carry the parent prefixed archive name`() {
    val root = ProjectBuilder.builder().withName("runtime-kotlin").build()
    val parent = ProjectBuilder.builder().withName("runtime-infra").withParent(root).build()
    val nested = ProjectBuilder.builder().withName("skills").withParent(parent).build()

    nested.pluginManager.apply(JvmLibraryConventionPlugin::class.java)

    assertEquals(
      "runtime-infra-skills",
      nested.tasks.named("jar", Jar::class.java).get().archiveBaseName.get(),
      "Nested runtime-infra module jars collide on flat names without the parent prefix.",
    )
  }
}
