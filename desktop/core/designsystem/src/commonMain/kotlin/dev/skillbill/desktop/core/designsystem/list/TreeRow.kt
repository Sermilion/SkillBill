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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
  inPane: Boolean = false,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
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
      .hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
      .then(if (expandable) Modifier.expandSemantics(expanded) else Modifier)
      .padding(start = if (inPane) paneBleed else treeBasePadding + treeIndent * level, end = if (inPane) paneBleed else 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides baseColor) {
      if (!inPane) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
          if (expandable) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
            SbIcon(LucideIcons.chevronRight, SbIconSize.Xs, modifier = Modifier.rotate(rotation))
          }
        }
      }
      // `.sb-tree-row > .sb-icon`: lead icons stay faint on every row state; status icons draw their own color.
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { lead() }
      id?.let { IdTag(it) }
      BasicText(title, Modifier.weight(1f), style = titleStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      // `join(o.meta)` puts each meta item straight into the row, so an empty meta adds no 8 px gap after the title.
      meta()
    }
  }
}
