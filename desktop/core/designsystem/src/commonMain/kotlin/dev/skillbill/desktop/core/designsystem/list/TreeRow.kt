package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.composite.expandSemantics
import dev.skillbill.desktop.core.designsystem.composite.focusBar
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.menuSpec

private val treeIndent = 18.dp
private val treeBasePadding = 12.dp
private val paneBleed = 8.dp

/** Negative side margins: the element grows [amount] past both edges of the space the layout gives it. */
private fun Modifier.bleedHorizontal(amount: Dp) = layout { measurable, constraints ->
  val extra = amount.roundToPx()
  val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2) else constraints
  val placeable = measurable.measure(wide)
  layout((placeable.width - extra * 2).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-extra, 0) }
}

/** [onContextMenu] receives the press position in row coordinates, or the row's start for Shift+F10 and the Menu key. */
@Composable
fun TreeRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  level: Int = 0,
  expandable: Boolean = false,
  expanded: Boolean = false,
  focused: Boolean = false,
  file: Boolean = false,
  muted: Boolean = false,
  lead: @Composable () -> Unit = {},
  id: String? = null,
  meta: @Composable RowScope.() -> Unit = {},
  chevron: (@Composable () -> Unit)? = null,
  rowAction: (@Composable RowScope.() -> Unit)? = null,
  inPane: Boolean = false,
  onContextMenu: ((Offset) -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  var rowSize by remember { mutableStateOf(IntSize.Zero) }
  var focusedWithin by remember { mutableStateOf(false) }
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
  val rotation = animateFloatAsState(if (expanded) 90f else 0f, menuSpec(), label = "tree-row-caret").value
  val baseColor = if (focused) colors.fg else colors.fg2
  val titleStyle = when {
    // `.is-muted > b` sets only color and line-through; the weight stays 400 on a file row (`.is-file > b`), 500 otherwise.
    muted -> (if (file) type.tBase else type.tBaseMedium).copy(color = colors.fgFaint, textDecoration = TextDecoration.LineThrough)
    file -> type.tBase.copy(color = colors.fg2)
    else -> type.tBaseMedium.copy(color = colors.fg)
  }
  Row(
    // `.sb-tree-row` has no background transition: hover and focus fills switch at once.
    // In a pane section (`.sb-pane-sec > .sb-tree-row`) the row is 28 px, pulled 8 px out on both sides with a 6 px radius.
    modifier.fillMaxWidth().then(if (inPane) Modifier.bleedHorizontal(paneBleed) else Modifier)
      .height(if (inPane) SkillBillSize.propertyRow else SkillBillSize.compactRow)
      .background(
        if (focused) colors.bgActive else if (hovered) colors.bgHover else Color.Transparent,
        if (inPane) RoundedCornerShape(SkillBillRadius.sm) else RectangleShape,
      ).focusBar(focused)
      .onSizeChanged { rowSize = it }.onFocusChanged { focusedWithin = it.hasFocus }.hoverable(interaction).then(menu)
      .onPreviewKeyEvent {
        val activationKey = when (it.key) {
          Key.Enter, Key.NumPadEnter, Key.Spacebar -> true
          else -> false
        }
        val modifiedActivation = activationKey &&
          (it.isCtrlPressed || it.isMetaPressed || it.isAltPressed || it.isShiftPressed)
        modifiedActivation
      }.clickable(interaction, indication = null, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
      .then(if (expandable) Modifier.expandSemantics(expanded) else Modifier)
      .padding(start = if (inPane) paneBleed else treeBasePadding + treeIndent * level, end = if (inPane) paneBleed else 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides baseColor) {
      if (!inPane) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
          if (chevron != null) chevron() else if (expandable) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
            SbIcon(LucideIcons.chevronRight, SbIconSize.Xs, modifier = Modifier.rotate(rotation))
          }
        }
      }
      // `.sb-tree-row > .sb-icon`: lead icons stay faint on every row state; status icons draw their own color.
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { lead() }
      id?.let { IdTag(it) }
      BasicText(title, Modifier.weight(1f), style = titleStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      // `join(o.meta)` puts each meta item straight into the row, so an empty meta adds no 8 px gap after the title.
      if (rowAction != null && (hovered || focusedWithin || focused)) rowAction() else meta()
    }
  }
}
