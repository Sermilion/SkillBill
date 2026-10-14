package dev.skillbill.tray.infrastructure

import dev.skillbill.tray.core.domain.model.scaledWindowPixels
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUiScaleTest {
  @Test
  fun `KDE Xwayland fractional scale applies to content and window bounds when AWT reports one`() {
    val config = Files.createTempDirectory("skillbill-scale")
    try {
      Files.writeString(config.resolve("kwinrc"), "[Xwayland]\nScale=1.5\n")
      val scale = resolveDesktopUiScale(mapOf("XDG_CURRENT_DESKTOP" to "KDE"), Properties(), config, "Linux", 1f)
      assertEquals(1.5f, scale.contentDensity)
      assertEquals(1590, scale.scaledWindowPixels(1060))
      assertEquals(1047, scale.scaledWindowPixels(698))
      val nativeScale = resolveDesktopUiScale(mapOf("XDG_CURRENT_DESKTOP" to "KDE"), Properties(), config, "Linux", 2f)
      assertNull(nativeScale.contentDensity)
      assertEquals(1060, nativeScale.scaledWindowPixels(1060))
    } finally {
      config.toFile().deleteRecursively()
    }
  }
}
