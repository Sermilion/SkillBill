package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.rememberHoverColor
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.tabular

/** `.sb-doc` gap: the space between the detail document's blocks. */
val DocBlockGap = 28.dp

/** `.sb-sec` gap: header to body. */
private val sectionBodyGap = 10.dp

/** `.sb-sec-hd`: 13/500 title at line height 1, a faint 400 count, 10 between items, the end slot pushed right with 6 between. */
@Composable
private fun SectionHeaderRow(
  title: String,
  count: String?,
  lead: (@Composable () -> Unit)?,
  actions: (@Composable RowScope.() -> Unit)?,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    lead?.invoke()
    BasicText(title, Modifier.semantics { heading() }, style = type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.em), maxLines = 1)
    count?.let { BasicText(it, style = type.tBase.tabular().copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.em), maxLines = 1) }
    trailing?.invoke()
    if (actions != null) {
      Spacer(Modifier.weight(1f))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
  }
}

/**
 * A [Section] header the operator can fold: the `.sb-sec-hd` row with a 12 dp chevron after the title and count, in the
 * sidebar section's colors (faint, muted on hover).
 */
@Composable
fun CollapsibleSectionHeader(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val chevronColor = rememberHoverColor(hovered, colors.fgFaint, colors.fgMuted).value
  SectionHeaderRow(
    title, count, lead, actions,
    modifier.hoverable(interaction)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
      .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
      .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    trailing = {
      CompositionLocalProvider(LocalContentColor provides chevronColor) {
        SbIcon(if (expanded) LucideIcons.chevronDown else LucideIcons.chevronRight, SbIconSize.Xs)
      }
    },
  )
}

@Composable
fun Section(
  title: String,
  modifier: Modifier = Modifier,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  body: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sectionBodyGap)) {
    SectionHeaderRow(title, count, lead, actions)
    body()
  }
}

/** The [DocBlockGap] above a lazy block; the document's first block passes zero. */
private fun LazyListScope.blockGap(key: Any, spaceBefore: Dp) {
  if (spaceBefore > 0.dp) item(key = "$key-gap") { Spacer(Modifier.height(spaceBefore)) }
}

/**
 * Lazy form of [Section]: the header pins while the rows that [content] adds scroll beneath it. The rows sit flush, so a box
 * is drawn from `boxSegment` items, and the list itself must not space its items. [headerBackground] must be opaque.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.section(
  key: Any,
  title: String,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  headerBackground: @Composable () -> Color = { SkillBillTheme.colors.bgPanel },
  spaceBefore: Dp = DocBlockGap,
  content: LazyListScope.() -> Unit,
) {
  blockGap(key, spaceBefore)
  stickyHeader(key) {
    SectionHeaderRow(title, count, lead, actions, Modifier.background(headerBackground()).padding(bottom = sectionBodyGap))
  }
  content()
}

/** [section] with a header that folds its rows away. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.collapsibleSection(
  key: Any,
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  headerBackground: @Composable () -> Color = { SkillBillTheme.colors.bgPanel },
  spaceBefore: Dp = DocBlockGap,
  content: LazyListScope.() -> Unit,
) {
  blockGap(key, spaceBefore)
  stickyHeader(key) {
    CollapsibleSectionHeader(
      title, expanded, onToggle,
      Modifier.background(headerBackground()).padding(bottom = if (expanded) sectionBodyGap else 0.dp),
      count, lead, actions,
    )
  }
  if (expanded) content()
}

@Composable
fun SbBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(modifier.fillMaxWidth().clip(shape).border(1.dp, SkillBillTheme.colors.borderSubtle, shape), content = content)
}
