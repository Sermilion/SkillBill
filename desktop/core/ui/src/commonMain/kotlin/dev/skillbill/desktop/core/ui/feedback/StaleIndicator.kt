package dev.skillbill.desktop.core.ui.feedback

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip

/** The view header's mark for a failed refresh: the screen still shows the last data it read. */
@Composable
fun StaleIndicator() {
  SbTooltip("Showing stale work data") {
    BasicText("Stale", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
  }
}
