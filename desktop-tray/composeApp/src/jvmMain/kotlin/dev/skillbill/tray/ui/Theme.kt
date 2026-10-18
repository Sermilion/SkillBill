package dev.skillbill.tray.ui

import androidx.compose.runtime.Composable
import dev.skillbill.tray.core.designsystem.DesignSystemTheme
import dev.skillbill.tray.core.domain.model.DesktopUiScaleConfiguration

@Composable
fun SkillBillTheme(uiScale: DesktopUiScaleConfiguration = DesktopUiScaleConfiguration(), content: @Composable () -> Unit) {
  DesktopUiScaleProvider(uiScale) {
    DesignSystemTheme(content)
  }
}
