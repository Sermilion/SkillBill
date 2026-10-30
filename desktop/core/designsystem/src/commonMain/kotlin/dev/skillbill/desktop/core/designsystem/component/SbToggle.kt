package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.menuSpec

private val TOGGLE_WIDTH = 28.dp
private val TOGGLE_HEIGHT = 16.dp
private val TOGGLE_KNOB = 12.dp
private val CHECKBOX_SIZE = 14.dp

@Composable
fun SbToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, enabled: Boolean = true, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val track = animateColorAsState(if (checked) colors.accent else colors.borderStrong, menuSpec(), label = "toggle-track").value
  val x = animateDpAsState(if (checked) 14.dp else 2.dp, menuSpec(), label = "toggle-knob").value
  val shape = SkillBillRadius.full
  val interaction = remember { MutableInteractionSource() }
  Box(modifier.size(TOGGLE_WIDTH, TOGGLE_HEIGHT).alpha(if (enabled) 1f else .45f)
    .background(track, shape)
    .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = interaction, indication = null, onValueChange = onCheckedChange)
    .handCursor(enabled)
    .semantics { contentDescription = label }
    .sbFocusRing(interaction, shape), contentAlignment = Alignment.CenterStart) {
    Box(Modifier.offset(x = x).size(TOGGLE_KNOB).background(if (checked) colors.accentFg else colors.fg, CircleShape))
  }
}

@Composable
fun SbCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, enabled: Boolean = true, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val interaction = remember { MutableInteractionSource() }
  Box(
    modifier.size(CHECKBOX_SIZE).alpha(if (enabled) 1f else .45f)
      .background(if (checked) colors.accent else androidx.compose.ui.graphics.Color.Transparent, shape)
      .border(1.dp, if (checked) colors.accent else colors.borderStrong, shape)
      .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onCheckedChange)
      .handCursor(enabled)
      .sbFocusRing(interaction, shape)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    if (checked) Canvas(Modifier.size(10.dp)) {
      val path = Path().apply { moveTo(size.width * 20 / 24, size.height * 6 / 24); lineTo(size.width * 9 / 24, size.height * 17 / 24); lineTo(size.width * 4 / 24, size.height * 12 / 24) }
      drawPath(path, colors.accentFg, style = Stroke(width = size.width * 3 / 24, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
  }
}
