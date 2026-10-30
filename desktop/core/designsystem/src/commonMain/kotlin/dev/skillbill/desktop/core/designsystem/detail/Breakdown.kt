package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

/** How many keys a [Breakdown] shows before its "+N more" toggle. */
const val BREAKDOWN_MAX_ROWS = 4

/** What a [Breakdown] draws: the [shown] rows and how many more sit behind the toggle ([hidden] is 0 when nothing folds). */
data class BreakdownRows(val shown: List<Pair<String, Int>>, val hidden: Int, val folds: Boolean)

/**
 * The display order of a breakdown: zero counts dropped, the largest count first (ties keep their order), at most [max] rows
 * while [open] is false. [folds] says whether there is a toggle at all.
 */
fun breakdownRows(counts: Map<String, Int>, open: Boolean, max: Int = BREAKDOWN_MAX_ROWS): BreakdownRows {
  val rows = counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
  val folds = rows.size > max
  val shown = if (open || !folds) rows else rows.take(max)
  return BreakdownRows(shown, rows.size - shown.size, folds)
}

/**
 * `.sb-bd`: a total split by key under a property. 22 px rows with the key in 11.5 px mono `fg-faint` and the count right-aligned
 * in 12 px `fg-muted` tabular; past [BREAKDOWN_MAX_ROWS] keys a ghost toggle reads "+N more", or "Show less" while [open].
 */
@Composable
fun Breakdown(counts: Map<String, Int>, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val rows = breakdownRows(counts, open)
  Column(modifier.fillMaxWidth()) {
    rows.shown.forEach { (key, count) ->
      Row(Modifier.fillMaxWidth().height(SkillBillSize.breakdownRow), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
          key, Modifier.weight(1f),
          style = type.monoSm.copy(color = colors.fgFaint, fontSize = type.tXs.fontSize),
          maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        BasicText(count.toString(), style = type.tSm.tabular().copy(color = colors.fgMuted, fontWeight = FontWeight.Normal), maxLines = 1)
      }
    }
    if (rows.folds) GhostToggle(if (open) "Show less" else "+${rows.hidden} more", onToggle)
  }
}

/** `.sb-bd-more`: 22 px, 6 px padding pulled 6 px left, 11.5 px `fg-faint`, `bg-hover` and `fg-muted` on hover. */
@Composable
private fun GhostToggle(label: String, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  Box(
    Modifier.pullStart(6)
      .height(SkillBillSize.breakdownRow)
      .background(if (hovered) colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
      .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = if (hovered) colors.fgMuted else colors.fgFaint, fontWeight = FontWeight.Normal), maxLines = 1)
  }
}

/** `margin-left: -Npx`: draws the element [dp] to the start of where the layout puts it. */
internal fun Modifier.pullStart(dp: Int) = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  layout(placeable.width, placeable.height) { placeable.placeRelative(-dp.dp.roundToPx(), 0) }
}
