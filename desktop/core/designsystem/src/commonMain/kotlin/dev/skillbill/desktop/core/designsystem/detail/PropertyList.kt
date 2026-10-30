package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor

private val propertyLabelWidth = 92.dp
private val propertyValueInset = 8.dp

/** The narrowest value cell the `.sb-prop` grid keeps; a [PropertyRow] narrower than its label plus this stacks. */
private val propertyMinValueWidth = 120.dp

@Composable
fun PropertyList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/**
 * `.sb-prop`: a [labelWidth] label column (92 dp) and a value cell that highlights when [onClick] makes it actionable. With
 * [stackedWhenNarrow], a row too narrow for the grid puts the label above the value.
 */
@Composable
fun PropertyRow(
  label: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  tooltip: String? = null,
  shortcut: String? = null,
  labelWidth: Dp = propertyLabelWidth,
  stackedWhenNarrow: Boolean = false,
  value: @Composable RowScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = onClick != null && interaction.collectIsHoveredAsState().value
  val fill = rememberHoverColor(hovered, Color.Transparent, colors.bgHover).value
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val textColor = if (hovered) colors.fg else colors.fg2
  val cell: @Composable (Boolean) -> Unit = { stacked ->
    val inset = if (stacked) 0.dp else propertyValueInset
    Row(
      Modifier.fillMaxWidth()
        .heightIn(min = 28.dp).background(fill, shape)
        .then(if (onClick != null) Modifier.hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = inset),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides textColor) { value() }
    }
  }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    if (stackedWhenNarrow && maxWidth < labelWidth + propertyMinValueWidth) {
      Column(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = type.tSm.copy(color = colors.fgMuted), maxLines = 1)
        if (tooltip != null) SbTooltip(tooltip, shortcut) { cell(true) } else cell(true)
      }
    } else {
      Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(labelWidth), style = type.tSm.copy(color = colors.fgMuted), maxLines = 2)
        Box(Modifier.weight(1f).bleedStart(propertyValueInset)) {
          if (tooltip != null) SbTooltip(tooltip, shortcut) { cell(false) } else cell(false)
        }
      }
    }
  }
}

/** `margin-left: -8px` on `.sb-prop > dd`: the cell grows [amount] into the label column so its text lines up with the column edge. */
private fun Modifier.bleedStart(amount: Dp) = layout { measurable, constraints ->
  val extra = amount.roundToPx()
  val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
  val placeable = measurable.measure(wide)
  val width = (placeable.width - extra).coerceAtLeast(0)
  layout(width, placeable.height) { placeable.placeRelative(-extra, 0) }
}
