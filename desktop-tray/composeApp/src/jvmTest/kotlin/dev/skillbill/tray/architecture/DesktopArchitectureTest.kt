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
    val presentation = sources.filter {
      "/ui/" in it.path || it.project in SourceRules.sharedUiModules || it.project == ":core:designsystem"
    }
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
    assertEquals(emptyList(), SourceRules.policyOwnershipViolations(desktopSources() + runtimeSources(), generated = generated()))
  }

  @Test
  fun `explicit run actions use the runtime launch port and the native display query stays approved`() {
    val workDataSources = desktopSources()
    val sources = workDataSources + runtimeSources()
    assertApprovedNativeDisplayQuery(sources)
    assertTrue(workDataSources.isNotEmpty(), "The governed production scan set is empty.")
    // The process-launching rule itself, with its rejection fixtures, is DeletionRulesTest.
    val launchConsumers = sources.filter { "import skillbill.ports.work.WorkRunLauncher" in it.text }
    assertTrue(launchConsumers.map { it.path.substringAfter("/src/main/kotlin/") }.toSet() == setOf(
      "skillbill/engine/work/control/WorkRunActions.kt",
      "skillbill/engine/work/control/WorkCancellation.kt",
      "skillbill/infrastructure/launcher/work/DetachedWorkRunLauncher.kt",
      "skillbill/di/core/RuntimeContext.kt",
      "skillbill/di/core/RuntimeWorkControlProvides.kt",
    ), "Run launch ownership changed: ${launchConsumers.map { it.path }}")
  }

  @Test
  fun `the governed edge walk follows a clean fixture and resolves aliased and extension edges, rejecting unresolved and empty-root edges`() {
    val clean = fixtureWalk("fixture/app/Clean.kt" to "package dev.skillbill.fixture.app\n\nfun control() { helper() }\n\nfun helper() {}\n")
    assertEquals(setOf("control", "helper"), clean.map(FunctionBody::name).toSet())

    // An aliased import reaches the declaration it names, not the alias; a missing target stays unresolved.
    val aliased = fixtureWalk(
      "fixture/target/Collaborator.kt" to "package dev.skillbill.fixture.target\n\nfun load() {}\n",
      "fixture/app/Alias.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.target.load as fetch\n\nfun control() { fetch() }\n",
    )
    assertTrue("fixture/target/Collaborator.kt:load" in aliased.map { "${it.path}:${it.name}" }, "The aliased import did not reach its target.")
    val aliasedMissing = assertFailsWith<IllegalStateException> {
      fixtureWalk("fixture/app/AliasVanished.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.vanished.load as fetch\n\nfun control() { fetch() }\n")
    }
    assertTrue("Unresolved governed edge at fixture/app/AliasVanished.kt:control: load" in aliasedMissing.message.orEmpty(), aliasedMissing.message)

    // An extension call reaches the extension function declared for its receiver.
    val extension = fixtureWalk(
      "fixture/target/Collaborator.kt" to "package dev.skillbill.fixture.target\n\nfun String.load() {}\n",
      "fixture/app/Extension.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.target.load\n\nfun control() { \"job\".load() }\n",
    )
    assertTrue("fixture/target/Collaborator.kt:load" in extension.map { "${it.path}:${it.name}" }, "The extension call did not reach its target.")

    val unresolved = assertFailsWith<IllegalStateException> {
      fixtureWalk("fixture/app/Vanished.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.vanished.helper\n\nfun control() { helper() }\n")
    }
    assertTrue("Unresolved governed edge at fixture/app/Vanished.kt:control: helper" in unresolved.message.orEmpty(), unresolved.message)

    // An import of generated output resolves to it and ends there; the same import with nothing generated stays unresolved.
    val usesResources = "fixture/app/Fonts.kt" to "package dev.skillbill.fixture.app\n\nimport dev.skillbill.fixture.generated.Res\n\nfun control() { Res() }\n"
    val resources = generatedDeclarations(listOf(Source("build/generated/Res.kt", "package dev.skillbill.fixture.generated\n\nobject Res\n")))
    assertEquals(setOf("dev.skillbill.fixture.generated.Res"), resources)
    assertEquals(setOf("control"), fixtureWalk(usesResources, generated = resources).map(FunctionBody::name).toSet())
    assertFailsWith<IllegalStateException> { fixtureWalk(usesResources) }

    assertFailsWith<AssertionError> { walkGovernedEdges(emptyList(), emptyList()) }
  }

  @Test
  fun `the generated output the governed sources import is scanned`() {
    val generated = generated()
    assertTrue("dev.skillbill.tray.core.designsystem.generated.resources.Res" in generated, "Compose resources were not scanned.")
    assertTrue("skillbill.di.core.create" in generated, "The runtime's kotlin-inject factories were not scanned.")
  }

  private fun generated(): Set<String> = generatedDeclarations(generatedSources())

  private fun fixtureWalk(vararg files: Pair<String, String>, generated: Set<String> = emptySet()): Set<FunctionBody> {
    val functions = parsedSymbols(files.map { (path, text) -> Source(path, text) })
    return walkGovernedEdges(functions, functions.filter { it.name == "control" }, generated)
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
      // Routes are @Serializable for saved state; the JSON tree types stay out of presentation.
      "kotlinx.serialization.json",
      "JsonObject",
      "JsonElement",
      "java.io.File",
      "java.nio.file",
      "File(",
    )
    val forbiddenCoreTokens = listOf("java.awt", "javax.swing", "androidx.compose", "ComposeWindow")
  }
}
