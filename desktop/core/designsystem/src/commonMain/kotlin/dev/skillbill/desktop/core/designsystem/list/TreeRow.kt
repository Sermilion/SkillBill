package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.composite.expandSemantics
import dev.skillbill.desktop.core.designsystem.composite.focusBar
import dev.skillbill.desktop.core.designsystem.composite.rowFill
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.menuSpec

private val treeIndent = 18.dp
private val treeBasePadding = 12.dp

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
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val rotation = animateFloatAsState(if (expanded) 90f else 0f, menuSpec(), label = "tree-row-caret").value
  val baseColor = if (focused) colors.fg else colors.fg2
  val titleStyle = when {
    muted -> type.tBaseMedium.copy(color = colors.fgFaint, textDecoration = TextDecoration.LineThrough)
    file -> type.tBase.copy(color = colors.fg2)
    else -> type.tBaseMedium.copy(color = colors.fg)
  }
  Row(
    modifier.fillMaxWidth().height(SkillBillSize.compactRow).rowFill(hovered, focused, false).focusBar(focused)
      .hoverable(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .then(if (expandable) Modifier.expandSemantics(expanded) else Modifier)
      .padding(start = treeBasePadding + treeIndent * level, end = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides baseColor) {
      Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (expandable) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
          SbIcon(LucideIcons.chevronRight, SbIconSize.Xs, modifier = Modifier.rotate(rotation))
        }
      }
      lead()
      id?.let { IdTag(it) }
      BasicText(title, Modifier.weight(1f), style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = meta)
    }
  }
}
