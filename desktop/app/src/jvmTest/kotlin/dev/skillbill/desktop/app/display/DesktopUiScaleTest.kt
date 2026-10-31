package dev.skillbill.desktop.app.display

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopUiScaleTest {
  private val kde = mapOf("XDG_CURRENT_DESKTOP" to "KDE")

  @Test
  fun `macOS and Windows keep the platform density without an override`() {
    withConfig { config ->
      Files.writeString(config.resolve("kwinrc"), "[Xwayland]\nScale=2\n")
      for (os in listOf("Mac OS X", "Windows 11")) {
        val scale = resolveDesktopUiScale(kde + ("GDK_SCALE" to "2"), Properties(), config, os, 1f)
        assertNull(scale.contentDensity)
        assertEquals(1400, scale.windowPixels(1400))
      }
    }
  }

  @Test
  fun `a Linux JVM that already reports a HiDPI scale keeps it`() {
    withConfig { config ->
      Files.writeString(config.resolve("kwinrc"), "[Xwayland]\nScale=1.5\n")
      val scale = resolveDesktopUiScale(kde, Properties(), config, "Linux", 2f)
      assertNull(scale.contentDensity)
      assertEquals(DpSize(1400.dp, 900.dp), scale.windowSize(DpSize(1400.dp, 900.dp)))
    }
  }

  @Test
  fun `KDE Xwayland fractional scale applies to content and window size when AWT reports one`() {
    withConfig { config ->
      Files.writeString(config.resolve("kwinrc"), "[General]\nScale=3\n[Xwayland]\nScale=1.5\n")
      val scale = resolveDesktopUiScale(kde, Properties(), config, "Linux", 1f)
      assertEquals(1.5f, scale.contentDensity)
      assertEquals(DpSize(2100.dp, 1350.dp), scale.windowSize(DpSize(1400.dp, 900.dp)))
      assertEquals(840, scale.windowDimension(560, 420).width)
      assertEquals(630, scale.windowDimension(560, 420).height)
    }
  }

  @Test
  fun `KDE output config uses the highest priority enabled output and falls back to the largest scale`() {
    withConfig { config ->
      val outputs = """{"name":"outputs","data":[{"scale":1.25},{"scale":2}]}"""
      Files.writeString(
        config.resolve("kwinoutputconfig.json"),
        """[$outputs,{"name":"setups","data":[{"outputs":[{"outputIndex":1,"enabled":false,"priority":0},{"outputIndex":0,"priority":1}]}]}]""",
      )
      assertEquals(1.25f, resolveDesktopUiScale(kde, Properties(), config, "Linux", 1f).contentDensity)
      Files.writeString(config.resolve("kwinoutputconfig.json"), "[$outputs]")
      assertEquals(2f, resolveDesktopUiScale(kde, Properties(), config, "Linux", 1f).contentDensity)
    }
  }

  @Test
  fun `environment scale factors win over KDE config and need a desktop session for KWin`() {
    withConfig { config ->
      Files.writeString(config.resolve("kwinrc"), "[Xwayland]\nScale=1.5\n")
      assertEquals(2f, resolveDesktopUiScale(kde + ("QT_SCALE_FACTOR" to "2"), Properties(), config, "Linux", 1f).contentDensity)
      assertEquals(1.25f, resolveDesktopUiScale(mapOf("GDK_SCALE" to "125%"), Properties(), config, "Linux", 1f).contentDensity)
      assertNull(resolveDesktopUiScale(emptyMap(), Properties(), config, "Linux", 1f).contentDensity)
    }
  }

  @Test
  fun `an explicit override applies on every OS relative to the platform density`() {
    withConfig { config ->
      val properties = Properties().apply { setProperty(SKILL_BILL_DESKTOP_UI_SCALE_PROPERTY, "150") }
      val mac = resolveDesktopUiScale(mapOf(SKILL_BILL_DESKTOP_UI_SCALE_ENV to "3"), properties, config, "Mac OS X", 2f)
      assertEquals(1.5f, mac.contentDensity)
      assertEquals(0.75f, mac.windowScale)
      val linux = resolveDesktopUiScale(mapOf(SKILL_BILL_DESKTOP_UI_SCALE_ENV to "2x"), Properties(), config, "Linux", 1f)
      assertEquals(2f, linux.contentDensity)
      assertEquals(2f, linux.windowScale)
    }
  }

  @Test
  fun `unreadable or out of range values are ignored`() {
    withConfig { config ->
      Files.writeString(config.resolve("kwinrc"), "[Xwayland]\nScale=9\n")
      Files.writeString(config.resolve("kwinoutputconfig.json"), "not json")
      val env = kde + ("GDK_SCALE" to "abc") + (SKILL_BILL_DESKTOP_UI_SCALE_ENV to "0.1")
      assertNull(resolveDesktopUiScale(env, Properties(), config, "Linux", 1f).contentDensity)
      assertNull(readKwinXwaylandScale(config.resolve("missing")))
    }
  }

  private fun withConfig(block: (Path) -> Unit) {
    val config = Files.createTempDirectory("skillbill-ui-scale")
    try {
      block(config)
    } finally {
      config.toFile().deleteRecursively()
    }
  }
}
