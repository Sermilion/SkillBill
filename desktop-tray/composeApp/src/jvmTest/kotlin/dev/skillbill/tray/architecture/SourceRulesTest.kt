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
    listOf(":core:common", ":core:domain", ":core:designsystem", ":core:testing", ":core:data", ":core:datastore").forEach { module ->
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
    )
    rejected.forEach { (expected, text) ->
      val violations = violationsFor(prod, text)
      assertTrue(violations.any { expected in it }, "Expected a violation mentioning '$expected' but got $violations")
    }
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
      "RoomBoardSource" to (main to "fun build() = RoomBoardSource()"),
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
