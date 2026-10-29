package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor

private val propertyLabelWidth = 92.dp
private val propertyValueInset = 8.dp

@Composable
fun PropertyList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** A non-null [onClick] makes the value cell actionable, with a hover highlight. */
@Composable
fun PropertyRow(
  label: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  tooltip: String? = null,
  shortcut: String? = null,
  value: @Composable RowScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = onClick != null && interaction.collectIsHoveredAsState().value
  val fill = rememberHoverColor(hovered, Color.Transparent, colors.bgHover).value
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val textColor = if (hovered) colors.fg else colors.fg2
  Row(modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(label, Modifier.width(propertyLabelWidth), style = type.tSm.copy(color = colors.fgMuted), maxLines = 1)
    val cell: @Composable () -> Unit = {
      Row(
        Modifier.offset(x = -propertyValueInset).heightIn(min = 28.dp).background(fill, shape)
          .then(if (onClick != null) Modifier.hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
          .padding(horizontal = propertyValueInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CompositionLocalProvider(LocalContentColor provides textColor) { value() }
      }
    }
    if (tooltip != null) SbTooltip(tooltip, shortcut) { cell() } else cell()
  }
}
