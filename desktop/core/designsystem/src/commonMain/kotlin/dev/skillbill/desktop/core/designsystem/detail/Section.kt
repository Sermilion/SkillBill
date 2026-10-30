package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** `.sb-doc` gap: the space between the detail document's blocks. */
val DocBlockGap = 28.dp

/** `.sb-sec` gap: header to body. */
private val sectionBodyGap = 10.dp

/**
 * Which detail sections the reader has collapsed, keyed by section key. Sections start from the default each call site gives,
 * and a toggle sticks for as long as the screen keeps this state.
 */
@Stable
class SectionCollapse internal constructor(private val toggled: SnapshotStateMap<String, Boolean>) {
  fun isCollapsed(key: Any, default: Boolean = false): Boolean = toggled[key.toString()] ?: default

  fun toggle(key: Any, default: Boolean = false) {
    toggled[key.toString()] = !isCollapsed(key, default)
  }
}

@Composable
fun rememberSectionCollapse(): SectionCollapse = remember { SectionCollapse(mutableStateMapOf()) }

/**
 * `.sb-sec-hd`: 13/500 title at line height 1, a faint 400 count, 10 between items, the end slot pushed right with 6 between.
 * With [onToggle] the title, count and a faint chevron form one button that collapses the section body.
 */
@Composable
private fun SectionHeaderRow(
  title: String,
  count: String?,
  lead: (@Composable () -> Unit)?,
  actions: (@Composable RowScope.() -> Unit)?,
  modifier: Modifier = Modifier,
  collapsed: Boolean = false,
  onToggle: (() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    lead?.invoke()
    val titleRow: @Composable RowScope.() -> Unit = {
      BasicText(title, Modifier.semantics { heading() }, style = type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.em), maxLines = 1)
      count?.let { BasicText(it, style = type.tBase.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.em), maxLines = 1) }
    }
    if (onToggle == null) {
      titleRow()
    } else {
      val interaction = remember { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      val shape = RoundedCornerShape(SkillBillRadius.xs)
      Row(
        Modifier.sbFocusRing(interaction, shape)
          .pointerHoverIcon(PointerIcon.Hand)
          .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
          .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        titleRow()
        CompositionLocalProvider(LocalContentColor provides if (hovered) colors.fgMuted else colors.fgFaint) {
          SbIcon(if (collapsed) LucideIcons.chevronRight else LucideIcons.chevronDown, 12.dp)
        }
      }
    }
    if (actions != null) {
      Spacer(Modifier.weight(1f))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
  }
}

@Composable
fun Section(
  title: String,
  modifier: Modifier = Modifier,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  collapsed: Boolean = false,
  onToggle: (() -> Unit)? = null,
  body: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sectionBodyGap)) {
    SectionHeaderRow(title, count, lead, actions, collapsed = collapsed, onToggle = onToggle)
    if (!collapsed) body()
  }
}

/** The [DocBlockGap] above a lazy block; the document's first block passes zero. */
private fun LazyListScope.blockGap(key: Any, spaceBefore: Dp) {
  if (spaceBefore > 0.dp) item(key = "$key-gap") { Spacer(Modifier.height(spaceBefore)) }
}

/**
 * Lazy form of [Section]: the header and the rows that [content] adds scroll together, as `.sb-sec` does. The rows sit flush, so a
 * box is drawn from [sbBoxSegment] items, and the list itself must not space its items. With [collapse] the header toggles the
 * section, and a collapsed section adds no rows; [collapsedByDefault] sets where it starts.
 */
fun LazyListScope.section(
  key: Any,
  title: String,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  spaceBefore: Dp = DocBlockGap,
  collapse: SectionCollapse? = null,
  collapsedByDefault: Boolean = false,
  content: LazyListScope.() -> Unit,
) {
  blockGap(key, spaceBefore)
  val collapsed = collapse?.isCollapsed(key, collapsedByDefault) ?: false
  item(key) {
    SectionHeaderRow(
      title, count, lead, actions,
      Modifier.padding(bottom = if (collapsed) 0.dp else sectionBodyGap),
      collapsed = collapsed,
      onToggle = collapse?.let { state -> { state.toggle(key, collapsedByDefault) } },
    )
  }
  if (!collapsed) content()
}

/** `.sb-box`: a 1px `--border-subtle` frame, radius 8, with the content inside the border, clipped to the inner corners. */
@Composable
fun SbBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth().sbBoxSegment(0, 1), content = content)
}

/**
 * Draws one lazy-list item as a segment of an [SbBox]. The CSS border takes 1px of the box, so the segment insets its content by
 * 1 on both sides, and on the top of the first and the bottom of the last; the border draws over the content, so a row's own
 * background (the current subtask's) never covers it, and the first and last segments draw the rounded corners.
 */
@Composable
fun Modifier.sbBoxSegment(index: Int, count: Int): Modifier {
  val first = index == 0
  val last = index == count - 1
  val radius = SkillBillRadius.md
  val inner = radius - 1.dp
  val clipShape = RoundedCornerShape(
    topStart = if (first) inner else 0.dp,
    topEnd = if (first) inner else 0.dp,
    bottomStart = if (last) inner else 0.dp,
    bottomEnd = if (last) inner else 0.dp,
  )
  val border = SkillBillTheme.colors.borderSubtle
  return drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val half = stroke / 2
    val r = radius.toPx()
    // A rounded frame that runs past the segment's open ends; clipping to the segment leaves only this segment's part.
    val top = if (first) half else -2 * r
    val bottom = if (last) size.height - half else size.height + 2 * r
    clipRect {
      drawRoundRect(
        border,
        topLeft = Offset(half, top),
        size = Size(size.width - stroke, bottom - top),
        cornerRadius = CornerRadius(r - half),
        style = Stroke(stroke),
      )
    }
  }.padding(start = 1.dp, end = 1.dp, top = if (first) 1.dp else 0.dp, bottom = if (last) 1.dp else 0.dp).clip(clipShape)
}
