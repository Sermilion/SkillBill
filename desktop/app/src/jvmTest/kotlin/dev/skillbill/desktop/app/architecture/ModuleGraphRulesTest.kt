package dev.skillbill.desktop.app.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphRulesTest {
  @Test
  fun `every included project has a manifest and the manifests satisfy the module graph rules`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    val manifests = loadManifests()
    assertEquals(ModuleGraphRules.includedProjects(Files.readString(desktopRoot.resolve("settings.gradle.kts"))), manifests.map { it.project }.toSet())
    assertEquals(emptyList(), ModuleGraphRules.violations(manifests))
  }

  @Test
  fun `the baseline fixture is clean so every rejection below is caused by its mutation`() {
    assertEquals(emptyList(), ModuleGraphRules.violations(baseline()))
  }

  @Test
  fun `each rule rejects a synthetic violation`() {
    val rejected = mapOf(
      "No architecture manifests" to emptyList<ModuleManifest>(),
      ":feature:board has a production edge to :feature:work" to baseline() + manifest(":feature:board", edges = listOf(":core:domain", ":feature:work")),
      ":core:domain resolves a framework or runtime dependency on jvmCompileClasspath: org.jetbrains.compose.runtime:runtime" to
        baseline { manifest -> if (manifest.project == ":core:domain") manifest.resolving("jvmCompileClasspath", "org.jetbrains.compose.runtime:runtime") else manifest },
      ":core:domain resolves a framework or runtime dependency on jvmRuntimeClasspath: dev.skillbill:runtime-core" to
        baseline { manifest -> if (manifest.project == ":core:domain") manifest.resolving("jvmRuntimeClasspath", "dev.skillbill:runtime-core") else manifest },
      ":core:common resolves the runtime on jvmRuntimeClasspath: project::runtime-kotlin:runtime-core" to
        baseline { manifest -> if (manifest.project == ":core:common") manifest.resolving("jvmRuntimeClasspath", "project::runtime-kotlin:runtime-core") else manifest },
    )
    rejected.forEach { (expected, manifests) ->
      val violations = ModuleGraphRules.violations(manifests)
      assertTrue(violations.any { expected in it }, "Expected a violation containing '$expected' but got $violations")
    }
  }

  private fun baseline(mutate: (ModuleManifest) -> ModuleManifest = { it }): List<ModuleManifest> = listOf(
    manifest(":app", edges = listOf(":core:common", ":core:domain", ":core:data"), resolved = listOf("project::runtime-kotlin:runtime-core")),
    manifest(":core:common"),
    manifest(":core:domain", edges = listOf(":core:common")),
    manifest(":core:data", edges = listOf(":core:common", ":core:domain"), resolved = listOf("dev.skillbill:runtime-core")),
  ).map(mutate)

  private fun manifest(project: String, edges: List<String> = emptyList(), resolved: List<String> = emptyList()) = ModuleManifest(
    project = project,
    projectDependencies = edges.map { "commonMainImplementation->$it" },
    resolved = mapOf("jvmCompileClasspath" to listOf("org.jetbrains.kotlin:kotlin-stdlib") + resolved),
  )

  private fun ModuleManifest.resolving(configuration: String, coordinate: String) =
    copy(resolved = resolved + (configuration to resolved[configuration].orEmpty() + coordinate))
}
