package dev.skillbill.desktop.app.architecture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val productionSourceSets = listOf("commonMain", "jvmMain")

internal data class ModuleManifest(
  val project: String,
  val projectDependencies: List<String>,
  val resolved: Map<String, List<String>>,
) {
  val productionEdges: Set<String>
    get() = projectDependencies
      .map { it.substringBefore("->") to it.substringAfter("->") }
      .filter { (configuration, _) -> productionSourceSets.any { configuration.startsWith(it) } }
      .mapTo(linkedSetOf()) { it.second }
}

internal fun parseManifest(text: String): ModuleManifest {
  val document = Json.parseToJsonElement(text).jsonObject
  return ModuleManifest(
    project = document.getValue("project").jsonPrimitive.content,
    projectDependencies = document.getValue("projectDependencies").jsonArray.map { it.jsonPrimitive.content },
    resolved = document.getValue("resolved").jsonObject.mapValues { (_, value) -> (value as JsonArray).map { it.jsonPrimitive.content } },
  )
}

internal fun loadManifests(): List<ModuleManifest> {
  val property = System.getProperty("skillbill.architecture.manifests").orEmpty()
  return property.split(File.pathSeparator).filter { it.isNotBlank() }.map { parseManifest(Files.readString(Path.of(it))) }
}

internal object ModuleGraphRules {
  const val DOMAIN = ":core:domain"
  private val runtimeReachers = setOf(":app", ":core:data")
  private val runtimeCoordinates = listOf("dev.skillbill:runtime-", "project::runtime-kotlin")
  private val domainForbidden = listOf("org.jetbrains.compose", "androidx.", "org.xerial:sqlite-jdbc") + runtimeCoordinates
  private val productionClasspaths = listOf("jvmCompileClasspath", "jvmRuntimeClasspath")

  fun violations(manifests: List<ModuleManifest>): List<String> = buildList {
    if (manifests.isEmpty()) add("No architecture manifests were supplied.")
    manifests.forEach { manifest ->
      val project = manifest.project
      if (project.startsWith(":feature:")) {
        manifest.productionEdges.filterNot { it.startsWith(":core:") }.forEach { add("$project has a production edge to $it; a feature reaches only core modules") }
      }
      productionClasspaths.forEach { name ->
        val coordinates = manifest.resolved[name].orEmpty()
        if (project == DOMAIN) {
          coordinates.filter { coordinate -> domainForbidden.any { coordinate.startsWith(it) } }
            .forEach { add("$project resolves a framework or runtime dependency on $name: $it") }
        } else if (project !in runtimeReachers) {
          coordinates.filter { coordinate -> runtimeCoordinates.any { coordinate.startsWith(it) } }
            .forEach { add("$project resolves the runtime on $name: $it") }
        }
      }
    }
  }

  /** The projects `settings.gradle.kts` includes; the implicit `:core` and `:feature` parents own no build script. */
  fun includedProjects(settings: String): Set<String> = Regex("""\binclude\(([^)]*)\)""").findAll(settings)
    .flatMap { block -> Regex("\"(:[^\"]*)\"").findAll(block.groupValues[1]) }.map { it.groupValues[1] }.toSet()
}
