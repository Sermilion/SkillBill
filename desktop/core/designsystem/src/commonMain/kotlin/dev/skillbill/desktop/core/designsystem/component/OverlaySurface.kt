package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow

enum class SbElevation { Popover, Modal }

@Composable
fun Modifier.sbOverlaySurface(elevation: SbElevation = SbElevation.Popover): Modifier {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(if (elevation == SbElevation.Popover) SkillBillRadius.md else SkillBillRadius.lg)
  val shadow = if (elevation == SbElevation.Popover) SkillBillTheme.shadows.popover else SkillBillTheme.shadows.modal
  return layeredShadow(shadow, shape)
    .clip(shape)
    .background(colors.bgOverlay, shape)
    .border(1.dp, colors.borderStrong, shape)
}
