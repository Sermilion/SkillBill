package dev.skillbill.desktop.app.architecture

import java.nio.file.Files
import java.nio.file.Path

internal data class ScannedSource(val path: String, val text: String)

/**
 * The app holds no work policy: ordering and deadline comparisons belong to the runtime and `:core:data`. A token scan of the
 * shell, `:core:ui` and every feature catches the realistic regression, a screen sorting rows or reading a deadline itself.
 */
internal object PolicyRules {
  private const val TEMP_ROOT_EXEMPTION = "core/data/src/jvmMain/kotlin/dev/skillbill/desktop/core/data/runtime/DesktopTempRoot.kt"
  private const val QUALITY_PLUGIN = "build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/QualityConventionPlugin.kt"
  private const val TEMP_DIR_HELPER = "build-logic/convention/src/main/kotlin/dev/skillbill/desktop/buildlogic/TestTempDir.kt"
  private const val CONVENTION_BUILD = "build-logic/convention/build.gradle.kts"
  private const val WRAPPER = "gradlew"
  private val importPattern = Regex("""^\s*import\s+(\S+)\s+as\s+(\w+)\s*$""", RegexOption.MULTILINE)
  private val zeroRootArgument = Regex("""\(\s*(?:(?:prefix|suffix|directory)\s*=\s*)?(?:\)|"|null\b|[A-Z][A-Z0-9_]*\s*[,)])""")
  private val sortCalls = listOf(
    "sorted", "sortedBy", "sortedByDescending", "sortedWith", "sortedDescending", "sortBy", "sortByDescending", "sortWith", "sortDescending",
  )
  private val deadlineFields = listOf("membershipDeadline", "freshnessDeadline")
  private val patterns = sortCalls.map { it to Regex("""\.\s*$it\s*[({]""") } + deadlineFields.map { it to Regex("""\b$it\b""") }

  /** Sort calls a file may make, each with a written reason; deadline patterns still run on every file. */
  private val allowedSorts = mapOf(
    "app/src/jvmMain/kotlin/dev/skillbill/desktop/app/DesktopLauncher.kt" to ("sorted" to "Orders the image's jar files for a stable classpath; no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/AssessmentOrder.kt" to
      ("sortedWith" to "Orders one goal's assessment findings by severity or status when the reader asks; no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/DetailPresentation.kt" to
      ("sortedWith" to "Orders a spec bundle's file names for display (manifest, spec.md, then by name); no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/ActivitySection.kt" to
      ("sortedBy" to "Orders one item's recorded activity facts oldest first for the feed; no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/QaChecklistActivity.kt" to
      ("sortedBy" to "Orders one QA run's recorded activity facts chronologically for the checklist feed; no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/ActivityProjection.kt" to
      ("sortedBy" to "Merges one workflow's phase and block events by time and lays its activity bar out by time; no work items."),
    "feature/detail/src/commonMain/kotlin/dev/skillbill/desktop/feature/detail/ActivitySummary.kt" to
      ("sortedByDescending" to "Ranks earlier model labels by recorded phase duration after the open phase model; no work items."),
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

  fun readSources(desktopRoot: Path, manifests: List<ModuleManifest>): List<ScannedSource> =
    readSourcesFrom(desktopRoot, manifests.filter { isScanned(it.project) })

  fun readTempScanSources(desktopRoot: Path, manifests: List<ModuleManifest>): List<ScannedSource> =
    readSourcesFrom(desktopRoot, manifests)

  private fun readSourcesFrom(desktopRoot: Path, manifests: List<ModuleManifest>): List<ScannedSource> = manifests
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

  fun sharedTempViolations(sources: List<ScannedSource>): List<String> {
    if (sources.isEmpty()) return listOf("SHARED_TEMP_ROOT found no desktop production sources to scan.")
    return sources.filterNot { it.path == TEMP_ROOT_EXEMPTION }.flatMap { source ->
      val code = stripCommentsOnly(source.text)
      val codePositions = stripStrings(code)
      val imports = importPattern.findAll(code).associate { it.groupValues[1] to it.groupValues[2] }
      val violations = mutableListOf<String>()
      if (Regex(receiverPattern("java.lang.System", imports, "getProperty") + "\\s*\\(\\s*\"java\\.io\\.tmpdir\"").containsCodeMatch(code, codePositions)) {
        violations += "System.getProperty(\"java.io.tmpdir\") reads the shared temp root."
      }
      listOf("java.nio.file.Path" to "of", "java.nio.file.Paths" to "get", "java.io.File" to "").forEach { (target, member) ->
        val pattern = receiverPattern(target, imports, member) + "\\s*\\(\\s*\"/tmp(?:/[^\"]*)?\""
        if (Regex(pattern).containsCodeMatch(code, codePositions)) violations += "$target uses a /tmp path literal."
      }
      listOf("java.nio.file.Files" to setOf("createTempFile", "createTempDirectory"), "java.io.File" to setOf("createTempFile"))
        .forEach { (target, members) -> members.forEach { member ->
          if (Regex(receiverPattern(target, imports, member) + "\\s*" + zeroRootArgument.pattern).containsCodeMatch(code, codePositions)) {
            violations += "$target.$member uses the default temp root."
          }
        } }
      setOf("createTempFile", "createTempDirectory").forEach { target ->
        (setOf("kotlin.io.path.$target", target) + listOfNotNull(imports["kotlin.io.path.$target"])).forEach { name ->
          val pattern = "(?<![\\w.])(?:kotlin\\.io\\.path\\.)?${Regex.escape(name)}\\s*${zeroRootArgument.pattern}"
          if (Regex(pattern).containsCodeMatch(code, codePositions)) violations += "kotlin.io.path.$target uses the default temp root."
        }
      }
      violations.map { "${source.path}: $it Use DesktopTempRoot as the temp root." }
    }
  }

  fun tempIsolationViolations(read: (String) -> String?): List<String> {
    val violations = mutableListOf<String>()
    val plugin = read(QUALITY_PLUGIN)?.let(::stripCommentsOnly)
    if (plugin == null) {
      violations += "$QUALITY_PLUGIN is missing."
    } else {
      val blocks = Regex("tasks\\.withType\\s*\\(\\s*Test::class\\.java\\s*\\)[^{]*\\{").findAll(plugin)
        .mapNotNull { match -> blockAt(plugin, match.range.last) }.toList()
      if (blocks.none { Regex("\\bisolateJvmTempDir\\s*\\(").containsMatchIn(it) }) {
        violations += "$QUALITY_PLUGIN must call isolateJvmTempDir() in the Test task configuration."
      }
    }
    val helper = read(TEMP_DIR_HELPER)?.let(::stripCommentsOnly)
    if (helper == null) violations += "$TEMP_DIR_HELPER is missing."
    else if (!helper.contains("-Djava.io.tmpdir=")) violations += "$TEMP_DIR_HELPER must pass -Djava.io.tmpdir=."
    val convention = read(CONVENTION_BUILD)?.let(::stripCommentsOnly)
    if (convention == null) violations += "$CONVENTION_BUILD is missing."
    else if (Regex("\\bwithType\\s*<\\s*Test\\s*>[^{]*\\{").findAll(convention)
        .mapNotNull { match -> blockAt(convention, match.range.last) }.none { it.contains("java.io.tmpdir") }) {
      violations += "$CONVENTION_BUILD must set java.io.tmpdir inside a Test task block."
    }
    val wrapper = read(WRAPPER)
    if (wrapper == null) violations += "$WRAPPER is missing."
    else if (!wrapper.contains("skill_bill_jvm_temp_root=")) violations += "$WRAPPER must set skill_bill_jvm_temp_root=."
    return violations
  }

  private fun receiverPattern(target: String, imports: Map<String, String>, member: String): String =
    "(?<![\\w.])(?:${(setOf(target, target.substringAfterLast('.')) + listOfNotNull(imports[target])).joinToString("|") { Regex.escape(it) }})\\s*" +
      (if (member.isEmpty()) "" else "\\.\\s*${Regex.escape(member)}")

  private fun blockAt(text: String, openBrace: Int): String? {
    var depth = 0
    for (index in openBrace until text.length) {
      when (text[index]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return text.substring(openBrace + 1, index)
        }
      }
    }
    return null
  }

  private fun Regex.containsCodeMatch(code: String, codePositions: String): Boolean =
    findAll(code).any { codePositions[it.range.first] != ' ' }

  private fun stripStrings(text: String): String =
    text.replace(Regex("(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\")")) { match ->
      val masked = CharArray(match.value.length) { ' ' }
      Regex("\\$\\{[^}]*}").findAll(match.value).forEach { expression ->
        expression.value.forEachIndexed { index, char -> masked[expression.range.first + index] = char }
      }
      String(masked)
    }

  private fun stripCommentsOnly(text: String): String =
    text.replace(Regex("(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|/\\*[\\s\\S]*?\\*/|//[^\\n]*)")) { match ->
      if (match.value.startsWith('"')) match.value else " ".repeat(match.value.length)
    }

  private fun stripCommentsAndStrings(text: String): String = text
    .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
    .replace(Regex("""//[^\n]*"""), " ")
    .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
    .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")
}
