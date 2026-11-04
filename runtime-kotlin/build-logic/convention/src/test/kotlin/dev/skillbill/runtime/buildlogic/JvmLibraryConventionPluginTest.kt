package dev.skillbill.runtime.buildlogic

import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import org.gradle.api.tasks.testing.Test as GradleTest

private const val MACOS_UNIX_SOCKET_PATH_CAP = 104

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
  fun `test workers write temp files under a per-task directory outside the shared temp root`() {
    val project = ProjectBuilder.builder().build()
    val testTask = project.tasks.register("installProbe", GradleTest::class.java).get()

    project.pluginManager.apply(JvmLibraryConventionPlugin::class.java)

    val tmpdirArgs =
      testTask.jvmArgumentProviders.flatMap { it.asArguments() }.filter { it.startsWith("-Djava.io.tmpdir=") }
    assertEquals(1, tmpdirArgs.size, "Expected exactly one java.io.tmpdir override, got $tmpdirArgs")
    val tempDir = File(tmpdirArgs.single().removePrefix("-Djava.io.tmpdir="))
    assertTrue(tempDir.name.startsWith(TEST_TEMP_PREFIX), "Unexpected temp dir name ${tempDir.name}")
    assertTrue(
      tempDir.path.length <= MACOS_UNIX_SOCKET_PATH_CAP,
      "Unix socket tests bind under java.io.tmpdir and macOS caps those paths at 104 bytes: $tempDir",
    )
    assertFalse(
      tempDir.startsWith(project.rootDir),
      "Test temp dirs must stay outside the checkout so non-repository paths remain available.",
    )
    assertFalse(
      tempDir.path.replace('\\', '/').contains("/.skill-bill/"),
      "Temp dirs under skill-bill state leak installed skill-classes into render tests: $tempDir",
    )
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
