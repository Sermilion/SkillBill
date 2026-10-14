package dev.skillbill.tray.architecture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal data class ModuleManifest(
  val project: String,
  val plugins: List<String>,
  val pluginClasses: List<String>,
  val targets: List<String>,
  val kspConfigurations: List<String>,
  val projectDependencies: List<String>,
  val resolved: Map<String, List<String>>,
) {
  val productionEdges: Set<String> get() = edgesFor(productionSourceSets)
  val testEdges: Set<String> get() = edgesFor(testSourceSets)

  private fun edgesFor(sourceSets: List<String>): Set<String> = projectDependencies
    .map { it.substringBefore("->") to it.substringAfter("->") }
    .filter { (configuration, _) -> sourceSets.any { configuration.startsWith(it) } }
    .mapTo(linkedSetOf()) { it.second }
}

internal fun parseManifest(text: String): ModuleManifest {
  val document = Json.parseToJsonElement(text).jsonObject
  fun strings(name: String): List<String> = document.getValue(name).jsonArray.map { it.jsonPrimitive.content }
  return ModuleManifest(
    project = document.getValue("project").jsonPrimitive.content,
    plugins = strings("plugins"),
    pluginClasses = strings("pluginClasses"),
    targets = strings("targets"),
    kspConfigurations = strings("kspConfigurations"),
    projectDependencies = strings("projectDependencies"),
    resolved = document.getValue("resolved").jsonObject.mapValues { (_, value) -> (value as JsonArray).map { it.jsonPrimitive.content } },
  )
}

internal fun loadManifests(): List<ModuleManifest> {
  val property = System.getProperty("skillbill.architecture.manifests").orEmpty()
  return property.split(File.pathSeparator).filter { it.isNotBlank() }.map { parseManifest(Files.readString(Path.of(it))) }
}

internal object ModuleGraphRules {
  val expectedProjects = setOf(":composeApp", ":core:common", ":core:domain", ":core:designsystem", ":core:testing")
  val allowedTargets = setOf("jvm:jvm", "metadata:common")
  val allowedProductionEdges: Map<String, Set<String>> = mapOf(
    ":composeApp" to setOf(":core:common", ":core:domain", ":core:designsystem"),
    ":core:common" to emptySet(),
    ":core:domain" to setOf(":core:common"),
    ":core:designsystem" to emptySet(),
    ":core:testing" to setOf(":core:common", ":core:domain"),
  )
  val allowedTestEdges: Map<String, Set<String>> = allowedProductionEdges.mapValues { (project, edges) ->
    if (project == ":core:testing") edges else edges + ":core:testing"
  }
  val consumedInProduction = listOf(":core:common", ":core:domain", ":core:designsystem")
  const val TESTING = ":core:testing"
  const val DOMAIN = ":core:domain"
  val domainClasspathForbidden = listOf(
    "org.jetbrains.compose", "androidx.compose", "org.jetbrains.androidx", "androidx.lifecycle", "androidx.room",
    "androidx.datastore", "androidx.navigation", "androidx.sqlite", "androidx.paging", "dev.nucleusframework",
    "dev.skillbill", "project::composeApp", "project::core:designsystem", "project::core:testing",
  )
  private val androidPlugin = Regex("^com\\.android\\.|KotlinAndroid")
  private val nonJvmKsp = Regex("^ksp(Android|Ios|Js|Wasm|Native|Linux|Macos|Mingw|Tvos|Watchos)")
  private val productionClasspaths = listOf("jvmCompileClasspath", "jvmRuntimeClasspath")

  fun violations(manifests: List<ModuleManifest>): List<String> = buildList {
    if (manifests.isEmpty()) add("No architecture manifests were supplied.")
    val projects = manifests.map { it.project }
    (expectedProjects - projects.toSet()).forEach { add("Missing architecture manifest for $it.") }
    (projects.toSet() - expectedProjects).forEach { add("Unexpected architecture manifest for $it.") }
    manifests.forEach { manifest ->
      val project = manifest.project
      if ("jvm:jvm" !in manifest.targets) add("$project declares no jvm target: ${manifest.targets}")
      manifest.targets.filter { it !in allowedTargets }.forEach { add("$project declares a non-JVM target: $it") }
      manifest.plugins.filter { "android" in it.lowercase() }.forEach { add("$project applies an Android plugin: $it") }
      manifest.pluginClasses.filter { androidPlugin.containsMatchIn(it) }.forEach { add("$project applies an Android plugin class: $it") }
      manifest.kspConfigurations.filter { nonJvmKsp.containsMatchIn(it) }.forEach { add("$project configures a non-JVM KSP target: $it") }
      (manifest.productionEdges - allowedProductionEdges[project].orEmpty()).forEach { add("$project has a disallowed production edge to $it") }
      (manifest.testEdges - allowedTestEdges[project].orEmpty()).forEach { add("$project has a disallowed test edge to $it") }
      if (TESTING in manifest.productionEdges) add("$project depends on $TESTING from a production configuration")
      if (project != TESTING) productionClasspaths.forEach { name ->
        if ("project:$TESTING" in manifest.resolved[name].orEmpty()) add("$project resolves $TESTING on $name")
      }
      if (project == DOMAIN) productionClasspaths.forEach { name ->
        manifest.resolved[name].orEmpty().filter { coordinate -> domainClasspathForbidden.any { coordinate.startsWith(it) } }
          .forEach { add("$project resolves a framework dependency on $name: $it") }
      }
    }
    addAll(cycles(manifests))
    consumedInProduction.forEach { module ->
      if (manifests.none { it.project != TESTING && it.project != module && module in it.productionEdges }) add("$module has no production consumer.")
    }
    if (manifests.none { it.project != TESTING && TESTING in it.testEdges }) add("$TESTING has no test consumer.")
  }

  private fun cycles(manifests: List<ModuleManifest>): List<String> {
    val graph = manifests.associate { it.project to (it.productionEdges + it.testEdges) }
    val state = mutableMapOf<String, Boolean>()
    val found = mutableListOf<String>()
    fun visit(node: String, path: List<String>) {
      when (state[node]) {
        true -> return
        false -> { found += "Module dependency cycle: ${(path.dropWhile { it != node } + node).joinToString(" -> ")}"; return }
        null -> Unit
      }
      state[node] = false
      graph[node].orEmpty().forEach { visit(it, path + node) }
      state[node] = true
    }
    graph.keys.forEach { visit(it, emptyList()) }
    return found
  }
}
