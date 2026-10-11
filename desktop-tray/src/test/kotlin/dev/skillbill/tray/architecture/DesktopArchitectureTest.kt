package dev.skillbill.tray.architecture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class DesktopArchitectureTest {
  private val sourceRoot = Path.of("src/main/kotlin/dev/skillbill/tray")

  @Test
  fun `source boundaries keep presentation free of adapters and process data APIs`() {
    val files = Files.walk(sourceRoot).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList() }
    val presentation = files.filter { "/presentation/" in it.toString() || "/ui/" in it.toString() }
    val domainApplication = files.filter { "/domain/" in it.toString() || "/application/" in it.toString() }
    val all = files.joinToString("\n") { Files.readString(it) }
    assertTrue(presentation.isNotEmpty())
    assertTrue(domainApplication.isNotEmpty())
    assertTrue(presentation.all { "dev.skillbill.tray.infrastructure" !in Files.readString(it) })
    assertTrue(presentation.all { file ->
      val source = Files.readString(file)
      !usesProcessApi(source) && forbiddenPresentationTokens.none { it in source }
    })
    assertTrue(domainApplication.all { file ->
      val source = Files.readString(file)
      !usesProcessApi(source) && forbiddenCoreTokens.none { token -> source.contains(token) }
    })
    assertFalse(listOf("skillbill.infrastructure.sqlite", "skillbill.ports.db", "java.sql", "org.sqlite", "SQLite", "JDBC", "jdbc:").any { it in all })
  }

  @Test
  fun `all required architecture packages exist`() {
    listOf("domain", "application", "infrastructure", "presentation", "ui", "composition").forEach { packageName ->
      assertTrue(Files.exists(sourceRoot.resolve(packageName)))
    }
  }

  private val forbiddenPresentationTokens = listOf("ProcessBuilder", "java.lang.Process", "kotlinx.serialization", "JsonObject", "JsonElement", "java.io.File", "java.nio.file", "File(")
  private val forbiddenCoreTokens = listOf("java.awt", "javax.swing", "androidx.compose", "ComposeWindow")
  private fun usesProcessApi(source: String): Boolean {
    val processAliases = Regex("import\\s+java\\.lang\\.Process(?:\\s+as\\s+(\\w+))?").findAll(source).map { it.groupValues[1].ifBlank { "Process" } }.toSet() + "Process"
    val runtimeAliases = Regex("import\\s+java\\.lang\\.Runtime(?:\\s+as\\s+(\\w+))?").findAll(source).map { it.groupValues[1].ifBlank { "Runtime" } }.toSet() + "Runtime"
    val hasProcessType = processAliases.any { alias -> Regex("(?<![\\w.])${Regex.escape(alias)}(?!\\w)").containsMatchIn(source) }
    val hasProcessBuilder = Regex("\\bProcessBuilder\\b").containsMatchIn(source)
    val hasRuntimeExec = runtimeAliases.any { alias -> Regex("\\b${Regex.escape(alias)}\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\b").containsMatchIn(source) }
    val hasUnqualifiedRuntimeExec = Regex("(?<![\\w.])getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\b").containsMatchIn(source)
    val hasQualifiedProcess = Regex("\\bjava\\s*\\.\\s*lang\\s*\\.\\s*Process\\b").containsMatchIn(source)
    val hasQualifiedRuntimeExec = Regex("\\bjava\\s*\\.\\s*lang\\s*\\.\\s*Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\b").containsMatchIn(source)
    return hasProcessType || hasProcessBuilder || hasRuntimeExec || hasUnqualifiedRuntimeExec || hasQualifiedProcess || hasQualifiedRuntimeExec
  }
}
