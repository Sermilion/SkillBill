package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.DarkSkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PeekOutputContrastTest {
  @Test
  fun `clickable output preserves the subtle fill so its filename remains readable`() = runComposeUiTest {
    setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        SkillBillTheme(reducedMotion = true) {
          Box(Modifier.width(320.dp).background(SkillBillTheme.colors.bgRaised).testTag("output")) {
            PeekOutput(LucideIcons.fileText, "spec_subtask_2.md", onClick = {})
          }
        }
      }
    }

    val background = onNodeWithTag("output").captureToImage().toPixelMap()[12, 3]
    val foreground = DarkSkillBillColors.fg
    assertTrue(contrast(foreground, background) >= 4.5f, "Filename contrast fell below 4.5 on $background")
  }

  @Test
  fun `faint phase labels and metadata remain readable on dark surfaces`() {
    val colors = DarkSkillBillColors
    listOf(colors.bgFrame, colors.bgPanel, colors.bgRaised, colors.bgOverlay, colors.bgControl).forEach { background ->
      assertTrue(contrast(colors.fgFaint, background) >= 4.5f, "Metadata contrast fell below 4.5 on $background")
    }
  }

  private fun contrast(foreground: Color, background: Color): Float =
    (foreground.luminance() + .05f) / (background.luminance() + .05f)
}
