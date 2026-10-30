package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.tabular

@Immutable
class Crumb(val label: String, val lead: (@Composable () -> Unit)? = null, val onClick: (() -> Unit)? = null)

/** The shell provides a sidebar toggle while the sidebar is a drawer; every [ViewHeader] shows it by default. */
val LocalSidebarToggle = staticCompositionLocalOf<(() -> Unit)?> { null }

/** [sidebarToggle] shows the sidebar toggle button when non-null; the shell decides when, this component holds no breakpoint. */
@Composable
fun ViewHeader(
  crumbs: List<Crumb>,
  modifier: Modifier = Modifier,
  middle: (@Composable () -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
  sidebarToggle: (() -> Unit)? = LocalSidebarToggle.current,
) {
  val colors = SkillBillTheme.colors
  Row(
    modifier.fillMaxWidth().height(SkillBillSize.header).hairline(Edge.Bottom, colors.borderSubtle).padding(start = 16.dp, end = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    sidebarToggle?.let { SbIconButton(LucideIcons.panelLeft, "Toggle sidebar", it, shortcut = "[") }
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, crumb ->
          if (index > 0) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronRight, SbIconSize.Xs) }
          CrumbItem(crumb, last = index == crumbs.lastIndex)
        }
      }
      if (middle != null) {
        // The header's 8 dp gap plus the slot's own 8 dp start margin.
        Box(Modifier.weight(1f, fill = false).padding(start = 16.dp).horizontalScroll(rememberScrollState())) { middle() }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
  }
}

@Composable
private fun RowScope.CrumbItem(crumb: Crumb, last: Boolean) {
  val colors = SkillBillTheme.colors
  val style = SkillBillTheme.type.tBaseMedium
  if (last) {
    Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      crumb.lead?.invoke()
      BasicText(crumb.label, style = style.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    return
  }
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val onClick = crumb.onClick
  val active = hovered && onClick != null
  val fill = rememberHoverColor(active, Color.Transparent, colors.bgHover).value
  Row(
    // Bleeds the 6 dp hover padding into the gaps so the label lines up with the header edge and the chevrons.
    Modifier.negativeHorizontalMargin(6.dp).background(fill, shape)
      .then(if (onClick != null) Modifier.hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
      .padding(horizontal = 6.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    crumb.lead?.invoke()
    BasicText(crumb.label, style = style.copy(color = if (active) colors.fg else colors.fgMuted), maxLines = 1)
  }
}

private fun Modifier.negativeHorizontalMargin(margin: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
  val bleed = margin.roundToPx()
  val widened = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + bleed * 2) else constraints
  val placeable = measurable.measure(widened.copy(minWidth = 0))
  val width = (placeable.width - bleed * 2).coerceAtLeast(0)
  layout(width, placeable.height) { placeable.placeRelative(-bleed, 0) }
}

@Composable
fun Pager(index: Int, total: Int, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText("${index + 1} / $total", style = SkillBillTheme.type.tSm.tabular().copy(color = SkillBillTheme.colors.fgFaint))
    SbIconButton(LucideIcons.chevronUp, "Previous", onPrevious, size = SbControlSize.Small, shortcut = "K")
    SbIconButton(LucideIcons.chevronDown, "Next", onNext, size = SbControlSize.Small, shortcut = "J")
  }
}
