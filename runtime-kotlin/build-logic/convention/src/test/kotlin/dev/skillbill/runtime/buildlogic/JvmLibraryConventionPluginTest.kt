package dev.skillbill.runtime.buildlogic

import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.gradle.api.tasks.testing.Test as GradleTest

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
