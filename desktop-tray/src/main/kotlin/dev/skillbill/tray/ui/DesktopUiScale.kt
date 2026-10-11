package dev.skillbill.tray.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.skillbill.tray.domain.DesktopUiScaleConfiguration

@Composable
internal fun DesktopUiScaleProvider(uiScaleConfiguration: DesktopUiScaleConfiguration, content: @Composable () -> Unit) {
  val baseDensity = LocalDensity.current
  val effectiveDensity = remember(baseDensity, uiScaleConfiguration.contentDensity) {
    uiScaleConfiguration.contentDensity?.let { Density(it, baseDensity.fontScale) } ?: baseDensity
  }
  CompositionLocalProvider(LocalDensity provides effectiveDensity, content = content)
}
