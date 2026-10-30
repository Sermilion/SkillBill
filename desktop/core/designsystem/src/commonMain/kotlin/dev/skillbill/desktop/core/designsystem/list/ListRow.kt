package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.tabular

private val idColumnWidth = 74.dp
private val timeColumnWidth = 64.dp
private val titleSummaryGap = 8.dp

/**
 * [focused] is the list cursor the screen owns, drawn as the active fill and the accent bar; it is not OS focus.
 * [onContextMenu] receives the press position in row coordinates, or the row's start for Shift+F10 and the Menu key.
 * [narrow] is the shell's narrow-window layout: the id column sizes to its text and the row's padding tightens to 16/12.
 */
@Composable
fun ListRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  id: String? = null,
  status: (@Composable () -> Unit)? = null,
  summary: String? = null,
  meta: @Composable RowScope.() -> Unit = {},
  time: String? = null,
  timeContent: (@Composable () -> Unit)? = null,
  compact: Boolean = false,
  focused: Boolean = false,
  selected: Boolean = false,
  faded: Boolean = false,
  divider: Boolean = true,
  narrow: Boolean = false,
  onContextMenu: ((Offset) -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  var rowSize by remember { mutableStateOf(IntSize.Zero) }
  val shape = if (compact) Modifier.clip(RoundedCornerShape(SkillBillRadius.sm)) else Modifier
  val menu = if (onContextMenu == null) Modifier else Modifier
    .pointerInput(onContextMenu) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent()
          if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
            onContextMenu(event.changes.first().position)
            event.changes.forEach { it.consume() }
          }
        }
      }
    }
    .onPreviewKeyEvent {
      val menuKey = it.key == Key.Menu || (it.key == Key.F10 && it.isShiftPressed)
      if (it.type == KeyEventType.KeyDown && menuKey) { onContextMenu(Offset(if (rtl) rowSize.width.toFloat() else 0f, rowSize.height / 2f)); true } else false
    }
  Row(
    // `.sb-row.faded` is element opacity, so it fades the fill, the focus bar and the hairline too, not only the content.
    modifier.then(if (faded) Modifier.alpha(0.6f) else Modifier)
      .fillMaxWidth().height(if (compact) SkillBillSize.compactRow else SkillBillSize.row)
      .then(shape).rowSurface(hovered, focused, selected, line = !compact && divider)
      .onSizeChanged { rowSize = it }.hoverable(interaction).then(menu)
      .pointerHoverIcon(PointerIcon.Hand).clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .semantics(mergeDescendants = true) { this.selected = selected }
      // A bottom border takes 1px of the row height, so the content centers in the space above it.
      .padding(start = if (compact) 12.dp else if (narrow) 16.dp else 20.dp, end = if (compact || narrow) 12.dp else 16.dp, bottom = if (!compact && divider) 1.dp else 0.dp),
    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (id != null) Box(if (compact || narrow) Modifier else Modifier.width(idColumnWidth)) { IdTag(id) }
    status?.invoke()
    TitleSummary(
      title = { BasicText(title, style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis) },
      summary = summary?.let { { BasicText(it, style = type.tSm.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
      modifier = Modifier.weight(1f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = meta)
    if (time != null || timeContent != null) {
      Box(if (compact) Modifier else Modifier.width(timeColumnWidth), contentAlignment = Alignment.CenterEnd) {
        // `.sb-row-time` is faint for both the finished time and a live clock.
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { timeContent?.invoke() }
        if (timeContent == null) BasicText(time.orEmpty(), style = type.tSm.tabular().copy(color = colors.fgFaint, textAlign = TextAlign.End), maxLines = 1)
      }
    }
  }
}

/**
 * The row's fill, focus bar and bottom hairline. `.sb-row` declares no transition, so the fill switches instantly; the focus bar
 * (`::before`, top 0 to bottom 0 of the padding box) stops above the bottom border.
 */
@Composable
private fun Modifier.rowSurface(hovered: Boolean, focused: Boolean, selected: Boolean, line: Boolean): Modifier {
  val colors = SkillBillTheme.colors
  val fill = when {
    selected -> colors.bgSelected
    focused -> colors.bgActive
    hovered -> colors.bgHover
    else -> Color.Transparent
  }
  val accent = colors.accent
  val border = colors.borderSubtle
  return drawBehind {
    if (fill != Color.Transparent) drawRect(fill)
    val stroke = 1.dp.toPx()
    val inner = if (line) size.height - stroke else size.height
    if (focused) {
      val bar = 2.dp.toPx()
      val x = if (layoutDirection == LayoutDirection.Rtl) size.width - bar else 0f
      drawRect(accent, topLeft = Offset(x, 0f), size = Size(bar, inner))
    }
    if (line) drawRect(border, topLeft = Offset(0f, inner), size = Size(size.width, stroke))
  }
}

@Composable
private fun TitleSummary(title: @Composable () -> Unit, summary: (@Composable () -> Unit)?, modifier: Modifier) {
  // Title and summary share a baseline, as `.sb-row-ti` aligns them (`align-items: baseline`).
  Layout(content = { title(); summary?.invoke() }, modifier = modifier) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0)
    val titlePlaceable = measurables[0].measure(loose)
    val gap = titleSummaryGap.roundToPx()
    val remaining = (constraints.maxWidth - titlePlaceable.width - gap).coerceAtLeast(0)
    val summaryPlaceable = measurables.getOrNull(1)?.takeIf { remaining > 0 }?.measure(Constraints(maxWidth = remaining, maxHeight = constraints.maxHeight))
    val titleBaseline = titlePlaceable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: titlePlaceable.height
    val summaryBaseline = summaryPlaceable?.let { p -> p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height } ?: 0
    val baseline = maxOf(titleBaseline, summaryBaseline)
    val titleY = baseline - titleBaseline
    val summaryY = baseline - summaryBaseline
    val height = maxOf(titleY + titlePlaceable.height, summaryPlaceable?.let { summaryY + it.height } ?: 0)
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else titlePlaceable.width + (summaryPlaceable?.let { gap + it.width } ?: 0)
    layout(width, height) {
      titlePlaceable.placeRelative(0, titleY)
      summaryPlaceable?.placeRelative(titlePlaceable.width + gap, summaryY)
    }
  }
}
