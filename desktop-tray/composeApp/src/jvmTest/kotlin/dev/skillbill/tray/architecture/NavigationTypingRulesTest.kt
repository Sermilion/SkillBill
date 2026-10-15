package dev.skillbill.tray.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationTypingRulesTest {
  @Test
  fun `navigation, lifetime, host and route sources carry typed arguments only`() {
    val scoped = NavigationTypingRules.scopedSources(desktopSources())
    listOf("/navigation/RouteSnapshot.kt", "/lifetime/WindowRegistry.kt", "/host/HostIntentRouter.kt", "/composition/WindowComponent.kt", "DetailRoutes.kt")
      .forEach { suffix -> assertTrue(scoped.any { it.path.endsWith(suffix) }, "The typing scan set does not contain $suffix.") }
    assertEquals(emptyList(), NavigationTypingRules.violations(scoped))
  }

  @Test
  fun `a string-keyed Any map and an unchecked cast suppression are rejected, aliased or not`() {
    val path = "composeApp/src/commonMain/kotlin/dev/skillbill/tray/navigation/Bad.kt"
    val rejected = mapOf(
      "Map<String, Any?>" to "class Bad(val args: Map<String, Any?>)",
      "MutableMap<String, Any>" to "class Bad { val args: MutableMap<String, Any> = mutableMapOf() }",
      "Dict<String, Any?>" to "import kotlin.collections.Map as Dict\nclass Bad(val args: Dict<String, Any?>)",
      "Map<Text, Value>" to "import kotlin.String as Text\nimport kotlin.Any as Value\nclass Bad(val args: Map<Text, Value>)",
      "UNCHECKED_CAST" to "@Suppress(\"UNCHECKED_CAST\")\nfun read(value: Any): List<String> = value as List<String>",
      "Hush" to "import kotlin.Suppress as Hush\n@Hush(\"UNCHECKED_CAST\")\nfun read(value: Any): List<String> = value as List<String>",
    )
    rejected.forEach { (expected, text) ->
      val violations = NavigationTypingRules.violations(listOf(Source(path, text)))
      assertTrue(violations.isNotEmpty(), "Expected a violation for $expected but got none.")
    }
    assertEquals(
      emptyList(),
      NavigationTypingRules.violations(listOf(Source(path, "class Ok(val titles: Map<String, String>, val ids: Map<Long, Any?>)"))),
    )
  }

  @Test
  fun `the typing scan covers only the navigation surfaces`() {
    assertTrue(NavigationTypingRules.inScope("composeApp/src/commonMain/kotlin/dev/skillbill/tray/navigation/WindowNavigator.kt"))
    assertTrue(NavigationTypingRules.inScope("feature/work/src/commonMain/kotlin/dev/skillbill/tray/feature/work/WorkRoute.kt"))
    assertTrue(!NavigationTypingRules.inScope("core/data/src/commonMain/kotlin/dev/skillbill/tray/core/data/Payload.kt"))
  }
}
