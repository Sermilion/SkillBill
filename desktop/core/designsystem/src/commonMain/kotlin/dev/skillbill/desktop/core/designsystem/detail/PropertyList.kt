package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.tabular

/** `.sb-prop > dd`: 6 px side padding, pulled 6 px into the gap so the value's text lines up with the column edge. */
private val propertyValueInset = 6.dp
private val propertyColumnGap = 12.dp
private val propertyValueHeight = 24.dp

/** `.sb-props`: rows stack with no gap; each row is 28 px. */
@Composable
fun PropertyList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth(), content = content)
}

/**
 * `.sb-prop`: an 80 px label column (12 px `fg-muted`, one line, [monoLabel] for phase names), a 12 px gap, then a 24 px value
 * cell that highlights when [onClick] makes it actionable. [sub] sits under the value in the value column, as a Breakdown does.
 */
@Composable
fun PropertyRow(
  label: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  tooltip: String? = null,
  shortcut: String? = null,
  labelWidth: Dp = SkillBillSize.propertyLabel,
  monoLabel: Boolean = false,
  sub: (@Composable ColumnScope.() -> Unit)? = null,
  value: @Composable RowScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = onClick != null && interaction.collectIsHoveredAsState().value
  // `.sb-prop > dd.is-btn:hover` declares no transition, so the fill switches at once; `.is-btn` sets `cursor: pointer`.
  val fill = if (hovered) colors.bgHover else Color.Transparent
  val textColor = if (hovered) colors.fg else colors.fg2
  val cell: @Composable () -> Unit = {
    Row(
      // `.sb-prop > dd`: exactly 24 px with `overflow: hidden`, so a long value is cut, never wrapped or grown.
      Modifier.fillMaxWidth()
        .height(propertyValueHeight).clipToBounds().background(fill, RoundedCornerShape(SkillBillRadius.sm))
        .then(if (onClick != null) Modifier.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = propertyValueInset),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides textColor) { value() }
    }
  }
  val labelStyle = if (monoLabel) {
    type.monoSm.copy(color = colors.fgMuted, fontSize = type.tXs.fontSize, lineHeight = 1.em)
  } else {
    type.tSm.copy(color = colors.fgMuted)
  }
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(SkillBillSize.propertyRow), verticalAlignment = Alignment.CenterVertically) {
      // `title` on the `dt`: the full label shows on hover when the ellipsis cuts it.
      SbTooltip(label) {
        BasicText(label, Modifier.width(labelWidth), style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }
      Spacer(Modifier.width(propertyColumnGap))
      Box(Modifier.weight(1f).bleedStart(propertyValueInset)) {
        if (tooltip != null) SbTooltip(tooltip, shortcut) { cell() } else cell()
      }
    }
    if (sub != null) {
      Column(Modifier.fillMaxWidth().padding(start = labelWidth + propertyColumnGap, bottom = 4.dp), content = sub)
    }
  }
}

/** The value cell's text: 12 px sans in the cell's color, tabular digits, one line with an ellipsis. */
@Composable
fun RowScope.PropertyText(text: String, color: Color = LocalContentColor.current) {
  BasicText(
    text,
    Modifier.weight(1f, fill = false),
    style = SkillBillTheme.type.tSm.tabular().copy(color = color, fontWeight = FontWeight.Normal),
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Ellipsis,
  )
}

/** `margin-left: -6px` on `.sb-prop > dd`: the cell grows [amount] into the gap so its text lines up with the column edge. */
private fun Modifier.bleedStart(amount: Dp) = layout { measurable, constraints ->
  val extra = amount.roundToPx()
  val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
  val placeable = measurable.measure(wide)
  val width = (placeable.width - extra).coerceAtLeast(0)
  layout(width, placeable.height) { placeable.placeRelative(-extra, 0) }
}
