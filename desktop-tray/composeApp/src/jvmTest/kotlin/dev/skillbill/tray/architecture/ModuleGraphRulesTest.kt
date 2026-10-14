package dev.skillbill.tray.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphRulesTest {
  @Test
  fun `generated manifests satisfy the module graph rules`() {
    val manifests = loadManifests()
    assertEquals(ModuleGraphRules.expectedProjects, manifests.map { it.project }.toSet())
    assertEquals(emptyList(), ModuleGraphRules.violations(manifests))
  }

  @Test
  fun `the desktop root holds no production source files`() {
    val rootSources = desktopRoot.resolve("src").takeIf(Files::isDirectory)?.let { root -> Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() } }.orEmpty()
    assertTrue(rootSources.isEmpty(), "Production sources belong to composeApp and core modules: $rootSources")
  }

  @Test
  fun `the baseline fixture is clean so every rejection below is caused by its mutation`() {
    assertEquals(emptyList(), ModuleGraphRules.violations(baseline()))
  }

  @Test
  fun `each rule rejects a synthetic violation`() {
    val rejected = mapOf(
      "No architecture manifests" to emptyList<ModuleManifest>(),
      "Missing architecture manifest for :core:common" to baseline().filterNot { it.project == ":core:common" },
      "non-JVM target: android:androidJvm" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(targets = manifest.targets + "android:androidJvm") else manifest
      },
      "no jvm target" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(targets = listOf("metadata:common")) else manifest
      },
      "Android plugin: com.android.library" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(plugins = manifest.plugins + "com.android.library") else manifest
      },
      "Android plugin class: com.android.build.gradle.LibraryPlugin" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(pluginClasses = manifest.pluginClasses + "com.android.build.gradle.LibraryPlugin") else manifest
      },
      "non-JVM KSP target: kspAndroid" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(kspConfigurations = manifest.kspConfigurations + "kspAndroid") else manifest
      },
      "disallowed production edge to :core:testing" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(projectDependencies = manifest.projectDependencies + "jvmMainImplementation->:core:testing") else manifest
      },
      "depends on :core:testing from a production configuration" to baseline { project, manifest ->
        if (project == ":core:designsystem") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainApi->:core:testing") else manifest
      },
      "disallowed production edge to :core:designsystem" to baseline { project, manifest ->
        if (project == ":core:domain") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainApi->:core:designsystem") else manifest
      },
      "disallowed test edge to :core:designsystem" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(projectDependencies = manifest.projectDependencies + "commonTestImplementation->:core:designsystem") else manifest
      },
      "Module dependency cycle" to baseline { project, manifest ->
        if (project == ":core:common") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainApi->:core:domain") else manifest
      },
      "resolves :core:testing on jvmRuntimeClasspath" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(resolved = manifest.resolved + ("jvmRuntimeClasspath" to listOf("project::core:testing"))) else manifest
      },
      "resolves a framework dependency on jvmCompileClasspath: org.jetbrains.compose.runtime:runtime" to baseline { project, manifest ->
        if (project == ":core:domain") manifest.copy(resolved = manifest.resolved + ("jvmCompileClasspath" to listOf("org.jetbrains.compose.runtime:runtime"))) else manifest
      },
      "resolves a framework dependency on jvmRuntimeClasspath: androidx.room3:room3-runtime" to baseline { project, manifest ->
        if (project == ":core:domain") manifest.copy(resolved = manifest.resolved + ("jvmRuntimeClasspath" to listOf("androidx.room3:room3-runtime"))) else manifest
      },
      ":core:designsystem has no production consumer" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:core:designsystem") }) else manifest
      },
      ":core:testing has no test consumer" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:core:testing") }) else manifest
      },
    )
    rejected.forEach { (expected, manifests) ->
      val violations = ModuleGraphRules.violations(manifests)
      assertTrue(violations.any { expected in it }, "Expected a violation containing '$expected' but got $violations")
    }
  }

  private fun baseline(mutate: (String, ModuleManifest) -> ModuleManifest = { _, manifest -> manifest }): List<ModuleManifest> =
    listOf(
      manifest(":composeApp", ":core:common", ":core:domain", ":core:designsystem", test = listOf(":core:testing")),
      manifest(":core:common"),
      manifest(":core:domain", ":core:common"),
      manifest(":core:designsystem"),
      manifest(":core:testing", ":core:common", ":core:domain"),
    ).map { mutate(it.project, it) }

  private fun manifest(project: String, vararg production: String, test: List<String> = emptyList()) = ModuleManifest(
    project = project,
    plugins = listOf("org.jetbrains.kotlin.multiplatform", "skillbill.kmp.library"),
    pluginClasses = listOf("org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPlugin"),
    targets = listOf("jvm:jvm", "metadata:common"),
    kspConfigurations = listOf("ksp", "kspJvm"),
    projectDependencies = production.map { "commonMainImplementation->$it" } + test.map { "jvmTestImplementation->$it" },
    resolved = mapOf("jvmCompileClasspath" to listOf("org.jetbrains.kotlin:kotlin-stdlib"), "jvmRuntimeClasspath" to listOf("org.jetbrains.kotlin:kotlin-stdlib")),
  )
}
