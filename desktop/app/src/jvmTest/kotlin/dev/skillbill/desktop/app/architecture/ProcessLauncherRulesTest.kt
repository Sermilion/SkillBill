package dev.skillbill.desktop.app.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessLauncherRulesTest {
  @Test
  fun `every declared production source is scanned and launchers stay on the allowlist`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    val violations = ProcessLauncherRules.violations(
      desktopRoot,
      Files.readString(desktopRoot.resolve("settings.gradle.kts")),
      loadManifests(),
    )
    assertEquals(emptyList(), violations)
  }
}
