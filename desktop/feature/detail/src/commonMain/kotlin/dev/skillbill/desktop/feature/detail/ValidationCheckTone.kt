package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
internal fun ValidationCheckState.tone(): Color {
  val colors = SkillBillTheme.colors
  return when (this) {
    ValidationCheckState.Passed -> colors.stDone
    ValidationCheckState.Running -> colors.stActive
    ValidationCheckState.Blocked -> colors.stBlocked
    ValidationCheckState.Failed -> colors.stFailed
    ValidationCheckState.Skipped, ValidationCheckState.Pending, ValidationCheckState.NotFinished -> colors.fgFaint
  }
}
