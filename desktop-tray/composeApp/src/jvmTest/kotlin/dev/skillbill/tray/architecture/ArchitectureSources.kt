package dev.skillbill.tray.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

internal data class Source(val path: String, val text: String, val project: String? = null, val sourceSet: String? = null)

internal val productionSourceSets = listOf("commonMain", "jvmMain")
internal val testSourceSets = listOf("commonTest", "jvmTest")

internal val desktopRoot: Path = systemPath("desktopRoot")
internal val runtimeRoot: Path = systemPath("runtimeRoot")

private fun systemPath(name: String): Path =
  Path.of(requireNotNull(System.getProperty(name)) { "The $name system property is not set by the Gradle test task." }).toAbsolutePath().normalize()

internal fun desktopSources(
  sourceSets: List<String> = productionSourceSets,
  manifests: List<ModuleManifest> = loadManifests(),
): List<Source> {
  check(manifests.isNotEmpty()) { "No architecture manifests were supplied." }
  val sources = manifests.flatMap { manifest ->
    val scanned = sourceSets.flatMap { sourceSet ->
      // Kotlin lists conventional directories even when they do not exist.
      manifest.sourceDirectories[sourceSet].orEmpty().map(desktopRoot::resolve).filter(Files::isDirectory).flatMap { root ->
        readSources(root, desktopRoot).map { it.copy(project = manifest.project, sourceSet = sourceSet) }
      }
    }.distinctBy(Source::path)
    if (sourceSets.any { it in productionSourceSets } && scanned.none { it.sourceSet in productionSourceSets }) {
      error("${manifest.project} production source directories ${manifest.directoriesFor(productionSourceSets)} contain no Kotlin sources")
    }
    scanned
  }
  check(sources.isNotEmpty()) { "The governed desktop scan set is empty." }
  return sources
}

internal fun runtimeSources(): List<Source> {
  val catalog = runtimeRoot.resolve("runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeModuleCatalog.kt")
  assertTrue(Files.isRegularFile(catalog), "Runtime module catalog is missing.")
  val modulesBlock = Files.readString(catalog).substringAfter("val declaredGradleModules").substringBefore("val moduleEdgeExpectations")
  val modules = Regex("\"([^\"]+)\"").findAll(modulesBlock).map { it.groupValues[1] }.toList()
  assertTrue(modules.isNotEmpty(), "Runtime module catalog declares no source roots.")
  return modules.filterNot { it == "runtime-infra" }.flatMap { module ->
    val root = runtimeRoot.resolve(module.replace(':', '/')).resolve("src/main/kotlin")
    assertTrue(Files.isDirectory(root), "Declared runtime source root is missing: $root")
    val files = readSources(root, null)
    assertTrue(files.isNotEmpty(), "Declared runtime source root is empty: $root")
    files
  }
}

internal fun readSources(root: Path, relativeTo: Path?): List<Source> = Files.walk(root).use { paths ->
  paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
    .map { path -> Source((relativeTo?.relativize(path) ?: path).toString().replace('\\', '/'), Files.readString(path)) }
    .toList()
}
