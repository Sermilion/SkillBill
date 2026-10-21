package dev.skillbill.tray.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRulesTest {
  @Test
  fun `real desktop sources satisfy the source rules`() {
    val sources = desktopSources(sourceSets = productionSourceSets + testSourceSets)
    assertEquals(emptyList(), SourceRules.violations(scanReferences(sources)))
  }

  @Test
  fun `every scanned module contributes sources so the rules are not vacuous`() {
    val sources = desktopSources()
    (listOf(":core:common", ":core:domain", ":core:designsystem", ":core:testing", ":core:data", ":core:datastore", ModuleGraphRules.UI) +
      ModuleGraphRules.features).forEach { module ->
      assertTrue(sources.any { it.project == module && it.sourceSet == "commonMain" }, "No commonMain sources scanned for $module.")
    }
    assertTrue(sources.any { it.project == ":composeApp" && it.sourceSet == "jvmMain" }, "No jvmMain sources scanned for :composeApp.")
    listOf(":core:data", ":core:datastore").forEach { module ->
      assertTrue(sources.any { it.project == module && it.sourceSet == "jvmMain" }, "No jvmMain sources scanned for $module.")
    }
    assertTrue(sources.any { it.path.contains("/composition/") }, "No composition sources scanned.")
  }

  @Test
  fun `clean control passes`() {
    assertEquals(emptyList(), violationsFor("core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/Clean.kt", "import kotlin.time.Instant\nclass Clean(val at: Instant)"))
  }

  @Test
  fun `each rule rejects a synthetic violation`() {
    val domain = "core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/Bad.kt"
    val common = "core/common/src/commonMain/kotlin/dev/skillbill/tray/core/common/Bad.kt"
    val app = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/Bad.kt"
    val rejected = mapOf(
      "java.nio.file.Path" to (common to "import java.nio.file.Path\nclass Bad"),
      "java.time.Instant" to (common to "import java.time.Instant as JavaInstant\nclass Bad"),
      "java.io.File" to (common to "class Bad(val file: java.io.File)"),
      "java.nio.file" to (common to "import java.nio.file.*\nclass Bad(val path: Path)"),
      "androidx.compose.runtime.Composable" to (domain to "import androidx.compose.runtime.Composable\nclass Bad"),
      "androidx.compose.runtime" to (domain to "import androidx.compose.runtime.*\nclass Bad(val state: androidx.compose.runtime.State<Int>)"),
      "dev.skillbill.tray.core.designsystem" to (domain to "import dev.skillbill.tray.core.designsystem.Theme as DomainTheme\nclass Bad"),
      "dev.skillbill.tray.infrastructure.Preferences" to (domain to "class Bad(val prefs: dev.skillbill.tray.infrastructure.Preferences)"),
      "skillbill.runtime.Anything" to (domain to "import skillbill.runtime.Anything\nclass Bad"),
      "dev.skillbill.tray.core.testing.TestClock" to (app to "import dev.skillbill.tray.core.testing.TestClock as Clock\nclass Bad"),
    )
    rejected.forEach { (expected, case) ->
      val (path, text) = case
      val violations = violationsFor(path, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' for $path but got $violations")
    }
  }

  @Test
  fun `adapter modules keep runtime, JDBC and preferences APIs in jvmMain and UI frameworks out`() {
    val dataCommon = "core/data/src/commonMain/kotlin/dev/skillbill/tray/core/data/Bad.kt"
    val dataJvm = "core/data/src/jvmMain/kotlin/dev/skillbill/tray/core/data/Ok.kt"
    val datastoreCommon = "core/datastore/src/commonMain/kotlin/dev/skillbill/tray/core/datastore/Bad.kt"
    val datastoreJvm = "core/datastore/src/jvmMain/kotlin/dev/skillbill/tray/core/datastore/Ok.kt"
    val app = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/Bad.kt"
    val domain = "core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/Bad.kt"
    val allowed = listOf(
      dataJvm to "import skillbill.runtime.Anything\nimport java.sql.Connection\nimport java.nio.file.Path\nclass Ok",
      datastoreJvm to "import java.util.prefs.Preferences\nimport java.nio.file.Path\nclass Ok",
    )
    allowed.forEach { (path, text) -> assertEquals(emptyList(), violationsFor(path, text), path) }

    val rejected = mapOf(
      "skillbill.runtime.Anything" to (dataCommon to "import skillbill.runtime.Anything as Runtime\nclass Bad"),
      "java.nio.file.Path" to (datastoreCommon to "class Bad(val path: java.nio.file.Path)"),
      "java.util.prefs.Preferences" to (datastoreCommon to "import java.util.prefs.Preferences\nclass Bad"),
      "androidx.compose.runtime.Composable" to (dataJvm to "import androidx.compose.runtime.Composable\nclass Bad"),
      "androidx.lifecycle.ViewModel" to (datastoreJvm to "import androidx.lifecycle.ViewModel\nclass Bad"),
      "androidx.navigation.NavController" to (dataCommon to "import androidx.navigation.NavController\nclass Bad"),
      "java.sql.Connection" to (app to "import java.sql.Connection\nclass Bad"),
      "skillbill.runtime" to (app to "class Bad(val runtime: skillbill.runtime.Anything)"),
      "org.sqlite.SQLiteConnection" to (domain to "import org.sqlite.SQLiteConnection\nclass Bad"),
    )
    rejected.forEach { (expected, case) ->
      val (path, text) = case
      val violations = violationsFor(path, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' for $path but got $violations")
    }
  }

  @Test
  fun `production sources reject reflective lookup that the generated graph cannot see`() {
    val prod = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/Bad.kt"
    val rejected = mapOf(
      "java.util.ServiceLoader" to "import java.util.ServiceLoader\nclass Bad",
      "ServiceLoader.load" to "class Bad { val all = ServiceLoader.load(Runnable::class.java) }",
      "Class.forName" to "class Bad { val type = Class.forName(\"x.Y\") }",
      "java.lang.Class.forName" to "class Bad { val type = java.lang.Class.forName(\"x.Y\") }",
      "kotlin.reflect.full.createInstance" to "import kotlin.reflect.full.createInstance\nclass Bad",
      "java.lang.reflect.Constructor" to "import java.lang.reflect.Constructor\nclass Bad",
      "java.lang.invoke" to "import java.lang.invoke.MethodHandles\nclass Bad",
    )
    rejected.forEach { (expected, text) ->
      val violations = violationsFor(prod, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' but got $violations")
    }
    assertEquals(
      emptyList(),
      violationsFor(
        "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/bootstrap/DesktopBootstrap.kt",
        "import java.lang.invoke.MethodHandles\nclass DesktopBootstrap",
      ),
    )
    assertEquals(emptyList(), violationsFor("composeApp/src/jvmTest/kotlin/dev/skillbill/tray/FooTest.kt", "import java.util.ServiceLoader\nclass FooTest"))
  }

  @Test
  fun `an inject constructor parameter with a default value is rejected`() {
    val path = "core/data/src/commonMain/kotlin/dev/skillbill/tray/core/data/Bad.kt"
    val rejected = mapOf(
      "Bad.limit" to "@Inject\nclass Bad(val limit: Int = 5)",
      "Bad.scope" to "class Bad @Inject constructor(val scope: String = \"x\")",
      "Bad.name" to "class Bad {\n  @Inject constructor(name: String = \"x\")\n}",
    )
    rejected.forEach { (expected, text) ->
      val violations = violationsFor(path, text)
      assertTrue(violations.any { "default value" in it && expected in it }, "Expected a default-value violation for '$expected' but got $violations")
    }
    assertEquals(emptyList(), violationsFor(path, "@Inject\nclass Ok(val limit: Int)\nclass Plain(val limit: Int = 5)"))
  }

  @Test
  fun `composition sources do not construct what the generated graph owns`() {
    val main = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/composition/Bad.kt"
    val commonComposition = "composeApp/src/commonMain/kotlin/dev/skillbill/tray/composition/Bad.kt"
    val dataDi = "core/data/src/commonMain/kotlin/dev/skillbill/tray/core/data/di/Bad.kt"
    val rejected = mapOf(
      "RoomWorkBoardRepository" to (main to "fun build() = RoomWorkBoardRepository()"),
      "DataStoreSettingsRepository" to (commonComposition to "fun build() = DataStoreSettingsRepository(handle)"),
      "DataStore" to (dataDi to "fun build() = DataStore()"),
      "RuntimeComponent" to (main to "fun build() = RuntimeComponent()"),
    )
    rejected.forEach { (expected, case) ->
      val (path, text) = case
      val violations = violationsFor(path, text)
      assertTrue(violations.any { "constructs $expected directly" in it }, "Expected a construction violation for $expected but got $violations")
    }
    assertEquals(emptyList(), violationsFor(main, "fun build() = HostEnvironment(emptyMap(), \"\", \"\")"))
    assertEquals(emptyList(), violationsFor("composeApp/src/jvmTest/kotlin/dev/skillbill/tray/composition/FooTest.kt", "fun build() = RoomWorkBoardRepository()"))
  }

  @Test
  fun `feature and toolkit common sources reject desktop, data, shell and sibling feature references`() {
    val work = "feature/work/src/commonMain/kotlin/dev/skillbill/tray/feature/work/Bad.kt"
    val history = "feature/history/src/commonMain/kotlin/dev/skillbill/tray/feature/history/Bad.kt"
    val toolkit = "core/ui/src/commonMain/kotlin/dev/skillbill/tray/core/ui/Bad.kt"
    val workTest = "feature/work/src/commonTest/kotlin/dev/skillbill/tray/feature/work/BadTest.kt"
    val rejected = mapOf(
      "androidx.compose.ui.window.Popup" to (work to "import androidx.compose.ui.window.Popup\nclass Bad"),
      "androidx.compose.ui.awt.ComposeWindow" to (history to "class Bad(val window: androidx.compose.ui.awt.ComposeWindow)"),
      "dev.nucleusframework.application.NucleusBackend" to (work to "import dev.nucleusframework.application.NucleusBackend as Backend\nclass Bad"),
      "dev.skillbill.tray.core.data.RoomWorkBoardRepository" to (work to "import dev.skillbill.tray.core.data.RoomWorkBoardRepository\nclass Bad"),
      "dev.skillbill.tray.core.datastore.SettingsStore" to (history to "class Bad(val store: dev.skillbill.tray.core.datastore.SettingsStore)"),
      "dev.skillbill.tray.application.TrayAvailability" to (work to "import dev.skillbill.tray.application.TrayAvailability\nclass Bad"),
      "dev.skillbill.tray.ui.MainWindow" to (toolkit to "import dev.skillbill.tray.ui.MainWindow\nclass Bad"),
      "dev.skillbill.tray.feature.history.HistoryViewModel" to (work to "import dev.skillbill.tray.feature.history.HistoryViewModel\nclass Bad"),
      "dev.skillbill.tray.feature.work.WorkViewModel" to (history to "class Bad(val work: dev.skillbill.tray.feature.work.WorkViewModel)"),
      "dev.skillbill.tray.feature.work.WorkUiState" to (toolkit to "import dev.skillbill.tray.feature.work.WorkUiState\nclass Bad"),
      "dev.skillbill.tray.core.data.Anything" to (workTest to "import dev.skillbill.tray.core.data.Anything\nclass BadTest"),
      "java.time.ZoneId" to (work to "import java.time.ZoneId\nclass Bad"),
      "dev.skillbill.tray.core.testing.TestClock" to (work to "import dev.skillbill.tray.core.testing.TestClock\nclass Bad"),
    )
    rejected.forEach { (expected, case) ->
      val (path, text) = case
      val violations = violationsFor(path, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' for $path but got $violations")
    }
    val allowed = listOf(
      work to "import dev.skillbill.tray.core.ui.PagedListState\nimport dev.skillbill.tray.core.domain.model.WorkItem\nimport dev.skillbill.tray.core.designsystem.ink\nclass Ok",
      toolkit to "import dev.skillbill.tray.core.domain.model.WorkItem\nclass Ok",
      workTest to "import dev.skillbill.tray.core.testing.TestClock\nclass OkTest",
      "core/ui/src/jvmMain/kotlin/dev/skillbill/tray/core/ui/Ok.kt" to "import androidx.compose.ui.window.Popup\nimport java.time.ZoneId\nclass Ok",
    )
    allowed.forEach { (path, text) -> assertEquals(emptyList(), violationsFor(path, text), path) }
  }

  @Test
  fun `production sources reject array and indexed-cast combine and the retired board types`() {
    val feature = "feature/work/src/commonMain/kotlin/dev/skillbill/tray/feature/work/Bad.kt"
    val app = "composeApp/src/jvmMain/kotlin/dev/skillbill/tray/application/Bad.kt"
    val combine = mapOf(
      "an indexed cast" to "val state = combine(a, b, c, d, e, f) { values -> values[0] as String }",
      "an optional indexed cast" to "val state = combine(flows) { values -> values[1] as? Int }",
      "a collection of flows" to "val state = combine(listOf(a, b)) { it.size }",
      "an array of flows" to "val state = combine(arrayOf(a, b)) { it.size }",
    )
    combine.forEach { (label, text) ->
      listOf(feature, app).forEach { path ->
        val violations = violationsFor(path, "class Bad {\n  $text\n}")
        assertTrue(violations.any { "typed combine overloads" in it }, "Expected a combine violation for $label in $path but got $violations")
      }
    }
    assertEquals(emptyList(), violationsFor(feature, "class Ok {\n  val state = combine(a, b, c) { x, y, z -> Triple(x, y, z) }\n  val first = list[0]\n}"))

    listOf("DashboardController", "DashboardState", "DashboardModel", "BoardSource", "RoomBoardSource").forEach { type ->
      val violations = violationsFor(app, "class Bad(val source: $type)")
      assertTrue(violations.any { "retired type $type" in it }, "Expected a retired-type violation for $type but got $violations")
    }
    assertEquals(emptyList(), violationsFor(app, "class Ok(val source: CliBoardSourceRetired)"))
  }

  @Test
  fun `a star import of a parent package is attributed to the forbidden member it reaches`() {
    val path = "core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/Bad.kt"
    val violations = violationsFor(path, "import androidx.compose.*\nclass Bad(val state: runtime.State<Int>)")
    assertTrue(violations.any { "androidx.compose" in it }, "Expected the parent star import to be rejected but got $violations")
  }

  @Test
  fun `testing helpers may depend on the testing package and tests may import it`() {
    assertEquals(
      emptyList(),
      violationsFor("core/testing/src/commonMain/kotlin/dev/skillbill/tray/core/testing/Fixtures.kt", "import dev.skillbill.tray.core.testing.TestClock\nclass Fixtures"),
    )
    assertEquals(
      emptyList(),
      violationsFor("composeApp/src/jvmTest/kotlin/dev/skillbill/tray/FooTest.kt", "import dev.skillbill.tray.core.testing.TestClock\nclass FooTest"),
    )
  }

  private fun violationsFor(path: String, text: String): List<String> {
    val module = path.substringBefore("/src/")
    val project = ":" + module.replace('/', ':')
    val source = Source(path, text, project, path.substringAfter("/src/").substringBefore('/'))
    return SourceRules.violations(scanReferences(listOf(source)))
  }
}
