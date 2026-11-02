package dev.skillbill.desktop.core.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

/** The Changes surfaces' monospace text: [size] sp at line height 1 unless [lineHeight] says otherwise. */
@Composable
fun changesMono(size: Float, weight: FontWeight, color: Color, lineHeight: Float = size): TextStyle =
  SkillBillTheme.type.monoSm.copy(color = color, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)

/** The Changes surfaces' sans text: [size] sp at line height 1 unless [lineHeight] says otherwise. */
@Composable
fun changesSans(size: Float, weight: FontWeight, color: Color, lineHeight: Float = size): TextStyle =
  SkillBillTheme.type.tSm.copy(color = color, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)

fun filesLabel(count: Int): String = if (count == 1) "1 file" else "$count files"

@Composable
fun AddCount(count: Int, modifier: Modifier = Modifier) {
  BasicText("+$count", modifier, style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.stDone), maxLines = 1, softWrap = false)
}

@Composable
fun DelCount(count: Int, modifier: Modifier = Modifier) {
  BasicText("−$count", modifier, style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.stFailed), maxLines = 1, softWrap = false)
}

/** A hairline along the bottom edge, drawn inside the bounds. */
fun Modifier.hairlineBottom(color: Color): Modifier = drawBehind {
  val stroke = 1.dp.toPx()
  drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

/** A hairline along the top edge, drawn inside the bounds. */
fun Modifier.hairlineTop(color: Color): Modifier = drawBehind {
  val stroke = 1.dp.toPx()
  drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

/** A hairline along the end edge, drawn inside the bounds. */
fun Modifier.hairlineEnd(color: Color): Modifier = drawBehind {
  val stroke = 1.dp.toPx()
  drawLine(color, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
}

/** The 28 dp Changes button: neutral, or tinted accent when [primary]. */
@Composable
fun ChangesButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  primary: Boolean = false,
  leadingIcon: ImageVector? = null,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(7.dp)
  val active = enabled && hovered
  val content = when {
    primary -> colors.accentText
    active -> colors.fg
    else -> colors.fg2
  }
  val border = when {
    primary -> colors.accent.copy(alpha = 0.4f)
    active -> colors.borderHover
    else -> colors.borderStrong
  }
  Row(
    modifier.alpha(if (enabled) 1f else 0.4f)
      .sbFocusRing(interaction, shape)
      .height(28.dp)
      .background(if (primary) colors.accentTint else colors.bgControl, shape)
      .border(1.dp, border, shape)
      .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leadingIcon?.let { CompositionLocalProvider(LocalContentColor provides content) { SbIcon(it, 13.dp, strokeWidth = 2.25f) } }
    BasicText(label, style = changesSans(11.5f, FontWeight.SemiBold, content), maxLines = 1, softWrap = false)
  }
}
