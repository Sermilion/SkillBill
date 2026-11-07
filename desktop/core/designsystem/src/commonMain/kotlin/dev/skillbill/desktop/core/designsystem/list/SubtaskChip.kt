package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbTagFrame
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.tabular

/**
 * `.sb-chip`: a dependency edge as an 18 dp button, 1 dp `border`, xs radius, 6 dp padding, 4 dp gap; the subtask's 14 px
 * StatusIcon and its [number] in 11.5 px mono. An [optional] edge has a dashed border and a faint "optional". Hover fills
 * `bg-hover` with `border-strong`.
 */
@Composable
fun SubtaskChip(number: String, status: StatusKind, optional: Boolean, tooltip: String, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val borderColor = if (hovered) colors.borderStrong else colors.border
  SbTooltip(tooltip) {
    Row(
      Modifier.hoverable(interaction).handCursor()
        .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
        .sbTagFrame(if (hovered) colors.bgHover else Color.Transparent, borderColor, dashed = optional),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      StatusIcon(status, null)
      BasicText(number, style = type.monoSm.tabular().copy(color = if (hovered) colors.fg else colors.fg2, fontSize = type.tXs.fontSize, fontWeight = FontWeight.Normal), maxLines = 1)
      if (optional) BasicText("optional", style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal), maxLines = 1)
    }
  }
}
