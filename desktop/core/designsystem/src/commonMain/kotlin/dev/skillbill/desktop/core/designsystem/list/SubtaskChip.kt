package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.tabular

/**
 * `.sb-chip`: a dependency edge as a 20 px button, 1 px `border`, 4 px radius, 6 px padding, 4 px gap; the subtask's 14 px
 * StatusIcon and its [number] in 11.5 px mono. An [optional] edge has a dashed border and a faint "optional". Hover fills
 * `bg-hover` with `border-strong`.
 */
@Composable
fun SubtaskChip(number: String, status: StatusKind, optional: Boolean, tooltip: String, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val borderColor = if (hovered) colors.borderStrong else colors.border
  val frame = if (optional) {
    Modifier.drawBehind {
      val stroke = 1.dp.toPx()
      drawRoundRect(
        borderColor,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(SkillBillRadius.xs.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
      )
    }
  } else {
    Modifier.border(1.dp, borderColor, shape)
  }
  SbTooltip(tooltip) {
    Row(
      Modifier.height(20.dp)
        .background(if (hovered) colors.bgHover else Color.Transparent, shape)
        .then(frame)
        .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
        .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
        // The CSS border takes 1 px inside the 20 px box before the 6 px padding; a drawn frame takes none, so pad 7.
        .padding(horizontal = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      StatusIcon(status, null)
      BasicText(number, style = type.monoSm.tabular().copy(color = if (hovered) colors.fg else colors.fg2, fontSize = type.tXs.fontSize, fontWeight = FontWeight.Normal), maxLines = 1)
      if (optional) BasicText("optional", style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal), maxLines = 1)
    }
  }
}
