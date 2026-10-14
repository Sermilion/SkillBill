package dev.skillbill.tray.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

internal data class Source(val path: String, val text: String)

internal val desktopModules = listOf("composeApp", "core/common", "core/domain", "core/designsystem", "core/testing")
internal val productionSourceSets = listOf("commonMain", "jvmMain")
internal val testSourceSets = listOf("commonTest", "jvmTest")

internal val desktopRoot: Path = systemPath("desktopRoot")
internal val runtimeRoot: Path = systemPath("runtimeRoot")

private fun systemPath(name: String): Path =
  Path.of(requireNotNull(System.getProperty(name)) { "The $name system property is not set by the Gradle test task." }).toAbsolutePath().normalize()

internal fun String.isDesktopSource(): Boolean = startsWith("composeApp/") || startsWith("core/")

internal fun desktopSources(modules: List<String> = desktopModules, sourceSets: List<String> = productionSourceSets): List<Source> =
  modules.flatMap { module ->
    sourceSets.flatMap { sourceSet ->
      val root = desktopRoot.resolve(module).resolve("src").resolve(sourceSet).resolve("kotlin")
      if (Files.isDirectory(root)) readSources(root, desktopRoot) else emptyList()
    }
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
