package dev.skillbill.tray.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeletionRulesTest {
  @Test
  fun `the real desktop tree satisfies the deletion rules over a nonempty scan`() {
    val sources = desktopSources()
    DeletionRules.requireNonEmptyScan(sources)
    assertEquals(emptyList(), DeletionRules.violations(scanReferences(sources)))
    assertEquals(emptyList(), DeletionRules.rootViolations(Files.readString(desktopRoot.resolve("build.gradle.kts")), rootSourceFiles()))
  }

  @Test
  fun `a scan that reached nothing fails instead of passing`() {
    assertFailsWith<AssertionError> { DeletionRules.requireNonEmptyScan(emptyList()) }
    val onlyShell = listOf(Source("composeApp/src/jvmMain/kotlin/A.kt", "", ":composeApp", "jvmMain"))
    assertFailsWith<AssertionError> { DeletionRules.requireNonEmptyScan(onlyShell) }
    val onlyTests = listOf(Source("core/data/src/jvmTest/kotlin/A.kt", "", ":core:data", "jvmTest"))
    assertFailsWith<AssertionError> { DeletionRules.requireNonEmptyScan(onlyTests) }
  }

  @Test
  fun `the root build rejects a compilation plugin and a root source file`() {
    assertEquals(emptyList(), DeletionRules.rootViolations("plugins {\n  base\n}\n", emptyList()))
    val rejected = mapOf(
      "Kotlin, Compose or KSP plugin (kotlin()" to ("plugins {\n  kotlin(\"jvm\")\n}\n" to emptyList()),
      "Kotlin, Compose or KSP plugin (org.jetbrains.compose)" to ("plugins {\n  id(\"org.jetbrains.compose\")\n}\n" to emptyList()),
      "Kotlin, Compose or KSP plugin (devtools.ksp)" to ("plugins {\n  id(\"com.google.devtools.ksp\")\n}\n" to emptyList()),
      "src/main/kotlin/Main.kt is a root source file" to ("plugins {\n  base\n}\n" to listOf("src/main/kotlin/Main.kt")),
    )
    rejected.forEach { (expected, case) ->
      val violations = DeletionRules.rootViolations(case.first, case.second)
      assertTrue(violations.any { expected in it }, "Expected a violation containing '$expected' but got $violations")
    }
  }

  @Test
  fun `each deletion rule rejects a synthetic violation and accepts its control`() {
    val app = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/Bad.kt"
    val data = "core/data/src/jvmMain/kotlin/dev/skillbill/tray/core/data/Bad.kt"
    val dataCommon = "core/data/src/commonMain/kotlin/dev/skillbill/tray/core/data/Bad.kt"
    val domain = "core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/Bad.kt"
    val work = "feature/work/src/commonMain/kotlin/dev/skillbill/tray/feature/work/Bad.kt"
    val repositories = "import dev.skillbill.tray.core.domain.repository"
    val rejected = listOf(
      "WorkBoardRepository and WorkDetailRepository" to (app to "class Combined : WorkBoardRepository, WorkDetailRepository"),
      "WorkControlRepository and WorkStoreSession" to (
        app to "$repositories.WorkControlRepository as Control\n$repositories.WorkStoreSession as Session\n" +
          "class Combined(c: Control, s: Session) : Control by c, Session by s"
        ),
      "WorkBoardRepository and WorkDetailRepository" to (app to "typealias Board = WorkBoardRepository\nclass Combined : Board, WorkDetailRepository"),
      "OS process" to (data to "fun go() = ProcessBuilder(\"sh\").start()"),
      "OS process" to (data to "import java.lang.ProcessBuilder as Launcher\nfun go() = Launcher(\"sh\")"),
      "OS process" to (dataCommon to "fun go() = java.lang.Runtime.getRuntime().exec(\"sh\")"),
      "OS process" to (app to "fun go() = ProcessHandle.current()"),
      "OS process" to (domain to "class Bad(val running: Process)"),
      "OS process" to (work to "val launch = ::ProcessBuilder"),
      "retired ReasonTooltip" to (work to "fun reason() = ReasonTooltip(\"truncated\")"),
      "retired ReasonTooltip" to (work to "import dev.skillbill.tray.core.ui.ReasonTooltip as OldReason\nfun reason() = OldReason(\"truncated\")"),
      "retired preference pollSeconds" to (dataCommon to "val pollSeconds = 5"),
      "retired preference cliOverride" to (domain to "class Bad(val settings: Settings) { val path = settings.cliOverride }"),
      "repeating timer fixedRateTimer" to (work to "val timer = fixedRateTimer(period = 1000) { }"),
      "repeating timer java.util.Timer" to (domain to "import java.util.Timer\nclass Bad"),
      "repeating timer ticker" to (data to "val ticks = ticker(1000)"),
      "repeating timer scheduleAtFixedRate" to (data to "fun go() = executor.scheduleAtFixedRate(task, 0, 1, SECONDS)"),
    )
    rejected.forEach { (expected, case) ->
      val (path, text) = case
      val violations = violationsFor(path, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' for $path but got $violations")
    }

    val accepted = listOf(
      app to "class OneWay : WorkBoardRepository",
      domain to "/** Runtime verdicts, not a Process. */\nval label = \"ProcessBuilder\"",
      "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeTrayHost.kt" to "fun probe() = ProcessBuilder(\"kscreen-doctor\").start()",
      DeletionRules.LEGACY_SETTINGS_FILE to "private const val KEY = \"cliOverride\"",
      "core/data/src/jvmTest/kotlin/dev/skillbill/tray/core/data/BadTest.kt" to "val pollSeconds = 5\nfun go() = ProcessBuilder(\"sh\").start()",
      work to "suspend fun later() { delay(4_000) }",
      "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/composition/Probe.kt" to "val timer = fixedRateTimer(period = 1000) { }",
    )
    accepted.forEach { (path, text) -> assertEquals(emptyList(), violationsFor(path, text), path) }
  }

  @Test
  fun `an import alias in one file does not rename a supertype in another file`() {
    val aliasing = Source(
      "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/A.kt",
      "import dev.skillbill.tray.core.domain.repository.WorkBoardRepository as Board\nval unused = 1",
      ":composeApp",
      "jvmMain",
    )
    val unrelated = Source(
      "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/B.kt",
      "interface Board\nclass Local : Board, WorkDetailRepository",
      ":composeApp",
      "jvmMain",
    )
    assertEquals(emptyList(), DeletionRules.violations(scanReferences(listOf(aliasing, unrelated))))
  }

  private fun rootSourceFiles(): List<String> =
    desktopRoot.resolve("src").takeIf(Files::isDirectory)?.let { root ->
      Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).map { desktopRoot.relativize(it).toString() }.toList() }
    }.orEmpty()

  private fun violationsFor(path: String, text: String): List<String> {
    val module = path.substringBefore("/src/")
    val source = Source(path, text, ":" + module.replace('/', ':'), path.substringAfter("/src/").substringBefore('/'))
    return DeletionRules.violations(scanReferences(listOf(source)))
  }
}
