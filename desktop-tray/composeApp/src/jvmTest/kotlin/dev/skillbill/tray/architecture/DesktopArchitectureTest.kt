package dev.skillbill.tray.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopArchitectureTest {
  @Test
  fun `domain and presentation stay outside storage and runtime adapters`() {
    val sources = desktopSources()
    val presentation = sources.filter { "/presentation/" in it.path || "/ui/" in it.path || it.project == ":core:designsystem" }
    val domain = sources.filter { it.project == ModuleGraphRules.DOMAIN }
    val application = sources.filter { "/application/" in it.path }

    assertTrue(domain.isNotEmpty())
    assertTrue(presentation.isNotEmpty())
    assertTrue(application.isNotEmpty())
    assertTrue(domain.all { !referencesAny(it.text, forbiddenStorageImports) })
    assertTrue(presentation.all { !referencesAny(it.text, forbiddenStorageImports) })
    assertTrue(application.all { !referencesAny(it.text, forbiddenApplicationImports) })
    assertTrue(presentation.all { source -> forbiddenPresentationTokens.none { it in source.text } })
    assertTrue((domain + application).all { source -> forbiddenCoreTokens.none { it in source.text } })
  }

  @Test
  fun `desktop surfaces render runtime policy verdicts and never recompute them`() {
    // The runtime sources resolve the adapter edges the walk follows out of the desktop; they are never roots or reported.
    assertEquals(emptyList(), SourceRules.policyOwnershipViolations(desktopSources() + runtimeSources()))
  }

  @Test
  fun `work data avoids process adapters and explicit run actions use the runtime launch port`() {
    val workDataSources = desktopSources()
    val sources = workDataSources + runtimeSources()
    assertApprovedNativeDisplayQuery(sources)
    assertTrue(workDataSources.isNotEmpty(), "The governed production scan set is empty.")
    workDataSources.forEach { source ->
      if (source.path !in approvedNativeHostProcessFiles) {
        assertFalse(referencesProcessAdapter(source.text), source.path)
      }
    }
    val launchConsumers = sources.filter { "import skillbill.ports.work.WorkRunLauncher" in it.text }
    assertTrue(launchConsumers.map { it.path.substringAfter("/src/main/kotlin/") }.toSet() == setOf(
      "skillbill/engine/work/control/WorkRunActions.kt",
      "skillbill/engine/work/control/WorkCancellation.kt",
      "skillbill/infrastructure/launcher/work/DetachedWorkRunLauncher.kt",
      "skillbill/di/core/RuntimeContext.kt",
      "skillbill/di/core/RuntimeWorkControlProvides.kt",
    ), "Run launch ownership changed: ${launchConsumers.map { it.path }}")
    assertNoReachableWorkDataProcessAdapter(sources)
  }

  @Test
  fun `the governed edge walk follows a clean fixture and rejects aliased, extension, unresolved and empty-root edges`() {
    val infra = "fixture/infra/ProcessRunner.kt" to "package dev.skillbill.fixture.infra\n\nclass ProcessRunner\n"

    val clean = fixtureWalk("fixture/app/Clean.kt" to "package dev.skillbill.fixture.app\n\nfun control() { helper() }\n\nfun helper() {}\n")
    assertEquals(setOf("control", "helper"), clean.map(FunctionBody::name).toSet())

    val aliased = assertFailsWith<AssertionError> {
      fixtureWalk(
        infra,
        "fixture/app/Alias.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.infra.ProcessRunner as Runner\n\nfun control() { Runner() }\n",
      )
    }
    assertTrue("fixture/app/Alias.kt:control" in aliased.message.orEmpty(), aliased.message)

    val extension = assertFailsWith<AssertionError> {
      fixtureWalk(
        infra,
        "fixture/app/Extension.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.infra.ProcessRunner\n\n" +
          "fun control() { \"job\".reach() }\n\nfun String.reach() { ProcessRunner() }\n",
      )
    }
    assertTrue("fixture/app/Extension.kt:reach" in extension.message.orEmpty(), extension.message)

    val unresolved = assertFailsWith<IllegalStateException> {
      fixtureWalk("fixture/app/Vanished.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.vanished.helper\n\nfun control() { helper() }\n")
    }
    assertTrue("Unresolved governed edge at fixture/app/Vanished.kt:control: helper" in unresolved.message.orEmpty(), unresolved.message)

    assertFailsWith<AssertionError> { walkGovernedEdges(emptyList(), emptyList()) }
  }

  private fun fixtureWalk(vararg files: Pair<String, String>): Set<FunctionBody> {
    val functions = parsedSymbols(files.map { (path, text) -> Source(path, text) })
    return walkGovernedEdges(functions, functions.filter { it.name == "control" })
  }

  private fun assertNoReachableWorkDataProcessAdapter(sources: List<Source>) {
    val functions = parsedSymbols(sources)
    assertTrue(functions.isNotEmpty(), "The governed function scan set is empty.")
    // The work-data owners that replaced RoomBoardSource: board and detail reads, runtime control, and the runtime adapter seam.
    listOf(
      "$DATA_PACKAGE_ROOT/repository/RoomWorkBoardRepository.kt" to "board",
      "$DATA_PACKAGE_ROOT/repository/RoomWorkDetailRepository.kt" to "detail",
      "$DATA_PACKAGE_ROOT/repository/RuntimeWorkControlRepository.kt" to "request",
      "$DATA_PACKAGE_ROOT/runtime/RuntimeWorkAdapter.kt" to "request",
      "$DATA_PACKAGE_ROOT/runtime/RuntimeWorkAdapter.kt" to "existingSnapshot",
    ).forEach { (path, name) ->
      assertTrue(functions.any { it.path.endsWith(path) && it.name == name }, "${path.substringAfterLast('/')}.$name is missing from the parsed graph.")
    }
    governedFunctionRoots.forEach { root ->
      assertTrue(functions.any { it.path.contains(root) }, "The governed function root is missing: $root")
    }
    val desktopPaths = sources.filter { it.project != null }.map(Source::path).toSet()
    val roots = functions.filter { function ->
      (function.path in desktopPaths &&
        (governedFunctionRoots.any { function.path.contains(it) } || function.name in controlEntryPoints))
        || (function.path.endsWith("/skillbill/engine/work/IdeStatusService.kt") && function.name == "board")
    }
    val visited = walkGovernedEdges(functions, roots)
    listOf(
      "/skillbill/engine/work/IdeStatusService.kt" to "board",
      "/skillbill/engine/work/IdeStatusService.kt" to "projectBoardSelection",
      "/skillbill/engine/work/IdeStatusService.kt" to "detail",
      "/skillbill/engine/work/control/WorkControlService.kt" to "request",
    ).forEach { (path, name) ->
      assertTrue(visited.any { it.path.endsWith(path) && it.name == name }, "Unresolved governed adapter edge: $path:$name")
    }
  }

  private fun assertApprovedNativeDisplayQuery(sources: List<Source>) {
    val placement = sources.single { it.path == "$DESKTOP_PACKAGE_ROOT/infrastructure/TrayPopoverPlacement.kt" }
    assertTrue(Regex("""\bProcessBuilder\s*\(""").findAll(placement.text).count() == 1)
    assertTrue(Regex("""ProcessBuilder\("kscreen-doctor", "-j"\)""").findAll(placement.text).count() == 1)
    assertFalse("Runtime.getRuntime().exec" in placement.text)
  }

  private fun importsAny(source: String, prefixes: List<String>): Boolean =
    source.lineSequence().map(String::trim).filter { it.startsWith("import ") }
      .map { it.removePrefix("import ").substringBefore(" as ").trim() }
      .any { imported -> prefixes.any { imported == it || imported.startsWith("$it.") } }

  private fun referencesAny(source: String, prefixes: List<String>): Boolean =
    importsAny(source, prefixes) || prefixes.any { prefix ->
      Regex("\\b${Regex.escape(prefix)}(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\b").containsMatchIn(source)
    }

  private fun referencesProcessAdapter(source: String): Boolean {
    val forbiddenImports = setOf("java.lang.Process", "java.lang.ProcessBuilder", "java.lang.ProcessHandle", "java.lang.Runtime")
    val aliases = source.lineSequence().map(String::trim).filter { it.startsWith("import ") && " as " in it }
      .mapNotNull { line ->
        val parts = line.removePrefix("import ").split(" as ", limit = 2)
        parts.lastOrNull()?.takeIf { parts.first() in forbiddenImports }
      }.toSet()
    val forbiddenNames = setOf("Process", "ProcessBuilder", "ProcessHandle", "Runtime") + aliases
    val names = forbiddenNames.joinToString("|") { Regex.escape(it) }
    return Regex("\\b(?:java\\.lang\\.)?(?:$names)\\b").containsMatchIn(source) ||
      Regex("\\b(?:java\\.lang\\.)?Runtime\\.getRuntime\\(\\)\\.exec\\b").containsMatchIn(source)
  }

  private companion object {
    val forbiddenStorageImports = listOf(
      "dev.skillbill.tray.infrastructure",
      "skillbill.engine",
      "skillbill.infrastructure",
      "java.sql",
      "org.sqlite",
      "androidx.room3",
      "androidx.paging",
      "androidx.sqlite",
      "androidx.datastore",
    )
    val forbiddenApplicationImports = listOf(
      "skillbill.engine",
      "skillbill.infrastructure.sqlite",
      "org.sqlite",
      "androidx.room3",
      "androidx.paging",
      "androidx.sqlite",
      "androidx.datastore",
      "java.sql",
    )
    val forbiddenPresentationTokens = listOf(
      "kotlinx.serialization",
      "JsonObject",
      "JsonElement",
      "java.io.File",
      "java.nio.file",
      "File(",
    )
    val forbiddenCoreTokens = listOf("java.awt", "javax.swing", "androidx.compose", "ComposeWindow")
    val controlEntryPoints = setOf("control", "pause", "stop", "perform")
    const val DATA_PACKAGE_ROOT = "/dev/skillbill/tray/core/data"
    val governedFunctionRoots = setOf("/composition/", "/application/", "/tray/core/data/")
    val approvedNativeHostProcessFiles = setOf(
      "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeServicesImpl.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeTrayHost.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/TrayPopoverPlacement.kt",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/macos/MacStatusItemBridge.kt",
    )
  }
}
