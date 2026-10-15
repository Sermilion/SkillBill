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
  val sourceDirectories: Map<String, List<String>>,
  /** Build output compiled with the source set (KSP, Room, Compose resources); edges resolve into it but never walk it. */
  val generatedDirectories: Map<String, List<String>> = emptyMap(),
) {
  val productionEdges: Set<String> get() = edgesFor(productionSourceSets)
  val testEdges: Set<String> get() = edgesFor(testSourceSets)

  fun directoriesFor(sourceSets: List<String>): List<String> = sourceSets.flatMap { sourceDirectories[it].orEmpty() }.distinct()

  private fun edgesFor(sourceSets: List<String>): Set<String> = projectDependencies
    .map { it.substringBefore("->") to it.substringAfter("->") }
    .filter { (configuration, _) -> sourceSets.any { configuration.startsWith(it) } }
    .mapTo(linkedSetOf()) { it.second }
}

internal fun parseManifest(text: String): ModuleManifest {
  val document = Json.parseToJsonElement(text).jsonObject
  fun strings(name: String): List<String> = document.getValue(name).jsonArray.map { it.jsonPrimitive.content }
  fun directories(name: String): Map<String, List<String>> =
    document.getValue(name).jsonObject.mapValues { (_, value) -> (value as JsonArray).map { it.jsonPrimitive.content } }
  return ModuleManifest(
    project = document.getValue("project").jsonPrimitive.content,
    plugins = strings("plugins"),
    pluginClasses = strings("pluginClasses"),
    targets = strings("targets"),
    kspConfigurations = strings("kspConfigurations"),
    projectDependencies = strings("projectDependencies"),
    resolved = document.getValue("resolved").jsonObject.mapValues { (_, value) -> (value as JsonArray).map { it.jsonPrimitive.content } },
    sourceDirectories = directories("sourceDirectories"),
    generatedDirectories = directories("generatedDirectories"),
  )
}

internal fun loadManifests(): List<ModuleManifest> {
  val property = System.getProperty("skillbill.architecture.manifests").orEmpty()
  return property.split(File.pathSeparator).filter { it.isNotBlank() }.map { parseManifest(Files.readString(Path.of(it))) }
}

internal object ModuleGraphRules {
  const val TESTING = ":core:testing"
  const val DOMAIN = ":core:domain"
  const val DATA = ":core:data"
  const val DATASTORE = ":core:datastore"
  const val UI = ":core:ui"
  val features = listOf(":feature:work", ":feature:detail", ":feature:history", ":feature:settings")
  val expectedProjects = setOf(":composeApp", ":core:common", DOMAIN, ":core:designsystem", TESTING, DATA, DATASTORE, UI) + features
  val allowedTargets = setOf("jvm:jvm", "metadata:common")

  /** A feature reaches only these modules: never data, datastore, the shell, testing or another feature. */
  val featureEdges = setOf(":core:common", DOMAIN, ":core:designsystem", UI)
  val allowedProductionEdges: Map<String, Set<String>> = mapOf(
    ":composeApp" to setOf(":core:common", DOMAIN, ":core:designsystem", DATA, DATASTORE, UI) + features,
    ":core:common" to emptySet(),
    DOMAIN to setOf(":core:common"),
    ":core:designsystem" to emptySet(),
    UI to setOf(":core:common", DOMAIN, ":core:designsystem"),
    TESTING to setOf(":core:common", DOMAIN),
    DATA to setOf(":core:common", DOMAIN, DATASTORE),
    DATASTORE to setOf(":core:common", DOMAIN),
  ) + features.associateWith { featureEdges }
  val allowedTestEdges: Map<String, Set<String>> = allowedProductionEdges.mapValues { (project, edges) ->
    if (project == TESTING) edges else edges + TESTING
  }
  val consumedInProduction = listOf(":core:common", DOMAIN, ":core:designsystem", DATA, DATASTORE, UI) + features
  val domainClasspathForbidden = listOf(
    "org.jetbrains.compose", "androidx.compose", "org.jetbrains.androidx", "androidx.lifecycle", "androidx.room",
    "androidx.datastore", "androidx.navigation", "androidx.sqlite", "androidx.paging", "dev.nucleusframework",
    "dev.skillbill", "project::runtime-kotlin", "project::composeApp", "project::core:designsystem", "project::core:testing",
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
      if (project == DOMAIN) {
        val allowedProjects = allowedProductionEdges.getValue(DOMAIN).map { "project:$it" }
        productionClasspaths.forEach { name ->
          val coordinates = manifest.resolved[name].orEmpty()
          coordinates.filter { coordinate -> domainClasspathForbidden.any { coordinate.startsWith(it) } }
            .forEach { add("$project resolves a framework dependency on $name: $it") }
          coordinates.filter { it.startsWith("project:") && it !in allowedProjects }
            .forEach { add("$project resolves a disallowed project component on $name: $it") }
        }
      }
      if (manifest.directoriesFor(productionSourceSets).isEmpty()) add("$project declares no production Kotlin source directories")
      if (project in features) {
        manifest.projectDependencies.map { it.substringBefore("->") to it.substringAfter("->") }
          .filter { (configuration, _) -> configuration.startsWith("jvmMain") }
          .forEach { (configuration, target) -> add("$project declares $configuration->$target; a feature's production code is commonMain only") }
      }
    }
    addAll(cycles(manifests))
    consumedInProduction.forEach { module ->
      if (manifests.none { it.project != TESTING && it.project != module && module in it.productionEdges }) add("$module has no production consumer.")
    }
    if (manifests.none { it.project != TESTING && TESTING in it.testEdges }) add("$TESTING has no test consumer.")
  }

  /** `settings.gradle.kts` includes the governed projects and nothing else; the implicit `:core` and `:feature` parents own no build script. */
  fun settingsViolations(settings: String): List<String> {
    val declared = Regex("""\binclude\(([^)]*)\)""").findAll(settings)
      .flatMap { block -> Regex("\"(:[^\"]*)\"").findAll(block.groupValues[1]) }.map { it.groupValues[1] }.toSet()
    return (expectedProjects - declared).map { "settings.gradle.kts does not include $it." } +
      (declared - expectedProjects).map { "settings.gradle.kts includes $it, which no architecture rule governs." }
  }

  /** A feature is shared UI and presentation: any production source outside `commonMain` is desktop code in the wrong place. */
  fun featureSourceSetViolations(sources: List<Source>): List<String> = sources
    .filter { it.project in features && it.sourceSet != "commonMain" }
    .map { "${it.project} has a ${it.sourceSet} production source ${it.path}; a feature's production code is commonMain only" }

  /**
   * An `api` project edge re-exports the target to every consumer, so it must earn that by showing one of the target's
   * types in a non-private declaration of the same source set. Anything else belongs in `implementation`.
   */
  fun apiEdgeViolations(manifests: List<ModuleManifest>, sources: List<Source>): List<String> = manifests.flatMap { manifest ->
    manifest.projectDependencies.map { it.substringBefore("->") to it.substringAfter("->") }
      .filter { (configuration, _) -> configuration.endsWith("Api") }
      .distinct()
      .filterNot { (configuration, target) ->
        val sourceSet = configuration.removeSuffix("Api")
        // Target-level configurations such as jvmApi carry no sources of their own; only source-set ones are judged.
        sourceSet !in productionSourceSets + testSourceSets ||
          exposesTypeOf(target, sources.filter { it.project == manifest.project && it.sourceSet == sourceSet })
      }
      .map { (configuration, target) -> "${manifest.project} declares $configuration->$target without exposing a type from $target" }
  }

  private fun exposesTypeOf(target: String, sources: List<Source>): Boolean {
    val targetPackage = "dev.skillbill.tray." + target.removePrefix(":").replace(':', '.')
    return sources.any { source ->
      val lines = source.text.lineSequence().map(String::trim).toList()
      val names = lines.filter { it.startsWith("import $targetPackage.") }.map { line ->
        val imported = line.removePrefix("import ")
        imported.substringAfter(" as ", imported.substringAfterLast('.'))
      }.map { Regex("\\b${Regex.escape(it)}\\b") }
      lines.filter { publicSignature.containsMatchIn(it) }.map { it.replace(privateProperty, "") }.any { line ->
        "$targetPackage." in line || names.any { it.containsMatchIn(line) }
      }
    }
  }

  private val publicSignature = Regex(
    "^(?!private\\b|internal\\b|protected\\b)(?:(?:public|override|open|abstract|data|sealed|enum|value|suspend|inline|operator|const|lateinit|annotation|fun\\s+interface)\\s+)*(?:class|interface|object|fun|val|var|typealias)\\b",
  )
  private val privateProperty = Regex("(?:private|internal|protected)\\s+va[lr]\\s+\\w+\\s*:\\s*[^,)]+")

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
