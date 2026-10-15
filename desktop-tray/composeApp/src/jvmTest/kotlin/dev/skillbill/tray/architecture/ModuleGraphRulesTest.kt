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
  fun `settings include exactly the governed projects`() {
    val settings = Files.readString(desktopRoot.resolve("settings.gradle.kts"))
    assertEquals(emptyList(), ModuleGraphRules.settingsViolations(settings))

    val dropped = ModuleGraphRules.settingsViolations(settings.replace("\":feature:history\", ", ""))
    assertTrue(dropped.any { "does not include :feature:history" in it }, "Got $dropped")
    val extra = ModuleGraphRules.settingsViolations(settings.replace("\":composeApp\",", "\":composeApp\", \":core:extra\","))
    assertTrue(extra.any { "includes :core:extra" in it }, "Got $extra")
    val separate = ModuleGraphRules.settingsViolations(settings.replace("include(", "include(\":core:extra\")\ninclude("))
    assertTrue(separate.any { "includes :core:extra" in it }, "Got $separate")
  }

  // The root holds no production source and applies no compilation plugin: see DeletionRulesTest.

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
      ":core:data has a disallowed production edge to :feature:board" to baseline { project, manifest ->
        if (project == ":core:data") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:feature:board") else manifest
      },
      ":core:data has a disallowed production edge to :core:testing" to baseline { project, manifest ->
        if (project == ":core:data") manifest.copy(projectDependencies = manifest.projectDependencies + "jvmMainImplementation->:core:testing") else manifest
      },
      ":core:datastore has a disallowed production edge to :core:data" to baseline { project, manifest ->
        if (project == ":core:datastore") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:core:data") else manifest
      },
      ":feature:work has a disallowed production edge to :core:data" to baseline { project, manifest ->
        if (project == ":feature:work") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:core:data") else manifest
      },
      ":feature:work has a disallowed production edge to :core:datastore" to baseline { project, manifest ->
        if (project == ":feature:work") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:core:datastore") else manifest
      },
      ":feature:history has a disallowed production edge to :feature:work" to baseline { project, manifest ->
        if (project == ":feature:history") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:feature:work") else manifest
      },
      ":feature:detail has a disallowed production edge to :composeApp" to baseline { project, manifest ->
        if (project == ":feature:detail") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:composeApp") else manifest
      },
      ":feature:settings depends on :core:testing from a production configuration" to baseline { project, manifest ->
        if (project == ":feature:settings") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:core:testing") else manifest
      },
      ":feature:settings declares jvmMainImplementation->:core:ui; a feature's production code is commonMain only" to baseline { project, manifest ->
        if (project == ":feature:settings") manifest.copy(projectDependencies = manifest.projectDependencies + "jvmMainImplementation->:core:ui") else manifest
      },
      ":core:ui has a disallowed production edge to :feature:work" to baseline { project, manifest ->
        if (project == ":core:ui") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:feature:work") else manifest
      },
      ":core:ui has a disallowed production edge to :core:data" to baseline { project, manifest ->
        if (project == ":core:ui") manifest.copy(projectDependencies = manifest.projectDependencies + "commonMainImplementation->:core:data") else manifest
      },
      "Missing architecture manifest for :feature:detail" to baseline().filterNot { it.project == ":feature:detail" },
      ":feature:history has no production consumer" to baseline { project, manifest ->
        if (project == ":composeApp") manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:feature:history") }) else manifest
      },
      ":core:ui has no production consumer" to baseline { _, manifest ->
        manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:core:ui") })
      },
      ":core:designsystem has no production consumer" to baseline { _, manifest ->
        manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:core:designsystem") })
      },
      // The shell, :core:ui and every feature test against it, so each of their edges goes.
      ":core:testing has no test consumer" to baseline { _, manifest ->
        manifest.copy(projectDependencies = manifest.projectDependencies.filterNot { it.endsWith("->:core:testing") })
      },
    )
    rejected.forEach { (expected, manifests) ->
      val violations = ModuleGraphRules.violations(manifests)
      assertTrue(violations.any { expected in it }, "Expected a violation containing '$expected' but got $violations")
    }
  }

  @Test
  fun `every feature keeps its production code in commonMain`() {
    val manifests = loadManifests()
    val sources = desktopSources(manifests = manifests)
    assertEquals(emptyList(), ModuleGraphRules.featureSourceSetViolations(sources))
    assertEquals(ModuleGraphRules.features.toSet(), sources.filter { it.project in ModuleGraphRules.features }.mapNotNull { it.project }.toSet())
  }

  @Test
  fun `a feature source outside commonMain is rejected`() {
    fun source(project: String, sourceSet: String) = Source("${project.removePrefix(":").replace(':', '/')}/src/$sourceSet/kotlin/A.kt", "", project, sourceSet)

    assertEquals(emptyList(), ModuleGraphRules.featureSourceSetViolations(listOf(source(":feature:work", "commonMain"), source(":core:ui", "jvmMain"))))
    val violations = ModuleGraphRules.featureSourceSetViolations(listOf(source(":feature:work", "jvmMain")))
    assertTrue(violations.any { ":feature:work has a jvmMain production source" in it }, "Got $violations")
  }

  @Test
  fun `every real api project edge exposes a type of its target`() {
    val manifests = loadManifests()
    assertEquals(emptyList(), ModuleGraphRules.apiEdgeViolations(manifests, desktopSources(manifests = manifests)))
    assertTrue(manifests.any { manifest -> manifest.projectDependencies.any { it.endsWith("Api->:core:domain") } }, "The api edge rule has no real edge to judge.")
  }

  @Test
  fun `an api edge is rejected unless a public declaration exposes a type of its target`() {
    val api = listOf("commonMainApi->:core:domain")
    val manifests = listOf(manifest(":core:testing", ":core:common").copy(projectDependencies = api))
    fun source(text: String) = Source("core/testing/src/commonMain/kotlin/dev/skillbill/tray/core/testing/Fixtures.kt", text, ":core:testing", "commonMain")
    val domainImport = "import dev.skillbill.tray.core.domain.model.Board\n"

    assertEquals(emptyList(), ModuleGraphRules.apiEdgeViolations(manifests, listOf(source("${domainImport}fun board(): Board = TODO()"))))
    assertEquals(emptyList(), ModuleGraphRules.apiEdgeViolations(manifests, listOf(source("fun board(): dev.skillbill.tray.core.domain.model.Board = TODO()"))))
    listOf(
      "no source at all" to emptyList(),
      "an import that nothing declares" to listOf(source("${domainImport}class Fixtures")),
      "a private declaration" to listOf(source("${domainImport}private fun board(): Board = TODO()")),
      "a private property" to listOf(source("${domainImport}class Fixtures(private val board: Board)")),
    ).forEach { (label, sources) ->
      val violations = ModuleGraphRules.apiEdgeViolations(manifests, sources)
      assertTrue(violations.any { "commonMainApi->:core:domain without exposing a type" in it }, "Expected a rejection for $label but got $violations")
    }
  }

  private fun baseline(mutate: (String, ModuleManifest) -> ModuleManifest = { _, manifest -> manifest }): List<ModuleManifest> =
    listOf(
      manifest(
        ":composeApp", ":core:common", ":core:domain", ":core:designsystem", ":core:data", ":core:datastore", ":core:ui",
        ":feature:work", ":feature:detail", ":feature:history", ":feature:settings", test = listOf(":core:testing"),
      ),
      manifest(":core:common"),
      manifest(":core:domain", ":core:common"),
      manifest(":core:designsystem"),
      manifest(":core:ui", ":core:common", ":core:domain", ":core:designsystem", test = listOf(":core:testing")),
      manifest(":core:testing", ":core:common", ":core:domain"),
      manifest(":core:data", ":core:common", ":core:domain", ":core:datastore"),
      manifest(":core:datastore", ":core:common", ":core:domain"),
    ).plus(ModuleGraphRules.features.map { feature ->
      manifest(feature, ":core:domain", ":core:designsystem", ":core:ui", test = listOf(":core:testing"))
    }).map { mutate(it.project, it) }

  private fun manifest(project: String, vararg production: String, test: List<String> = emptyList()) = ModuleManifest(
    project = project,
    plugins = listOf("org.jetbrains.kotlin.multiplatform", "skillbill.kmp.library"),
    pluginClasses = listOf("org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPlugin"),
    targets = listOf("jvm:jvm", "metadata:common"),
    kspConfigurations = listOf("ksp", "kspJvm"),
    projectDependencies = production.map { "commonMainImplementation->$it" } + test.map { "jvmTestImplementation->$it" },
    resolved = mapOf("jvmCompileClasspath" to listOf("org.jetbrains.kotlin:kotlin-stdlib"), "jvmRuntimeClasspath" to listOf("org.jetbrains.kotlin:kotlin-stdlib")),
    sourceDirectories = mapOf("commonMain" to listOf("${project.removePrefix(":").replace(':', '/')}/src/commonMain/kotlin")),
  )
}
