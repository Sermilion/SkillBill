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
    listOf(":core:common", ":core:domain", ":core:designsystem", ":core:testing").forEach { module ->
      assertTrue(sources.any { it.project == module && it.sourceSet == "commonMain" }, "No commonMain sources scanned for $module.")
    }
    assertTrue(sources.any { it.project == ":composeApp" && it.sourceSet == "jvmMain" }, "No jvmMain sources scanned for :composeApp.")
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
