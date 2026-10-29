package dev.skillbill.desktop.app.architecture

import java.nio.file.Files
import java.nio.file.Path

internal data class ScannedSource(val path: String, val text: String)

/**
 * The app holds no work policy: ordering and deadline comparisons belong to the runtime and `:core:data`. A token scan of the
 * shell, `:core:ui` and every feature catches the realistic regression, a screen sorting rows or reading a deadline itself.
 */
internal object PolicyRules {
  private val sortCalls = listOf(
    "sorted", "sortedBy", "sortedByDescending", "sortedWith", "sortedDescending", "sortBy", "sortByDescending", "sortWith", "sortDescending",
  )
  private val deadlineFields = listOf("membershipDeadline", "freshnessDeadline")
  private val patterns = sortCalls.map { it to Regex("""\.\s*$it\s*[({]""") } + deadlineFields.map { it to Regex("""\b$it\b""") }

  /** Sort calls a file may make, each with a written reason; deadline patterns still run on every file. */
  private val allowedSorts = mapOf(
    "app/src/jvmMain/kotlin/dev/skillbill/desktop/app/DesktopLauncher.kt" to ("sorted" to "Orders the image's jar files for a stable classpath; no work items."),
  )

  fun isScanned(project: String): Boolean = project == ":app" || project == ":core:ui" || project.startsWith(":feature:")

  fun violations(sources: List<ScannedSource>): List<String> {
    if (sources.isEmpty()) return listOf("The policy scan found no production sources.")
    return sources.flatMap { source ->
      val code = stripCommentsAndStrings(source.text)
      val allowedSort = allowedSorts[source.path]?.first
      patterns.filter { (name, pattern) -> name != allowedSort && pattern.containsMatchIn(code) }.map { (name, _) -> "${source.path} uses $name; ordering and deadlines are runtime policy" }
    }
  }

  fun readSources(desktopRoot: Path, manifests: List<ModuleManifest>): List<ScannedSource> = manifests.filter { isScanned(it.project) }
    .flatMap { manifest -> manifest.sourceDirectories.filterKeys { it == "commonMain" || it == "jvmMain" }.values.flatten() }
    .map { desktopRoot.resolve(it).normalize() }
    .filter(Files::isDirectory)
    .flatMap { root ->
      Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.map { path ->
          ScannedSource(desktopRoot.relativize(path).toString().replace('\\', '/'), Files.readString(path))
        }.toList()
      }
    }
    .distinctBy { it.path }

  private fun stripCommentsAndStrings(text: String): String = text
    .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
    .replace(Regex("""//[^\n]*"""), " ")
    .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
    .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")
}
