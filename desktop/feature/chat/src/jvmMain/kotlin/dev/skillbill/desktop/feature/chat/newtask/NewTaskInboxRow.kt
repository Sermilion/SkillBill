package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.dataColorFor
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
internal fun InboxGroupHeader(day: InboxDay) {
  val colors = SkillBillTheme.colors
  Box(
    Modifier.fillMaxWidth().height(26.dp).background(colors.bgPanel).padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
    contentAlignment = Alignment.BottomStart,
  ) {
    BasicText(day.label.uppercase(), style = inboxTextStyle(10.5.sp).copy(color = colors.fgFaint, letterSpacing = .04.em))
  }
}

@Composable
internal fun InboxRunRow(row: InboxItem.Run, now: Instant, selected: Boolean, shortcut: Int?, divider: Boolean, onOpenRun: (WorkItem) -> Unit) {
  val item = row.item
  val colors = SkillBillTheme.colors
  InboxRow(
    onClick = { onOpenRun(item) },
    blocked = row.blocked,
    selected = selected,
    shortcut = shortcut,
    divider = divider,
    leading = { InboxGlyph(if (row.blocked) InboxGlyphKind.Blocked else InboxGlyphKind.Running, if (row.blocked) colors.stBlocked else colors.stActive) },
    prefix = {
      BasicText(item.issueKey ?: item.id, style = inboxTextStyle(11.5.sp, mono = true).copy(color = colors.fgMuted), maxLines = 1)
    },
    middle = { highlighted ->
      InboxTitle(item.title, item.exactPauseReason ?: item.reason ?: item.summary.takeIf(String::isNotBlank), monoSecondary = false, highlighted = highlighted)
    },
    badge = { SbLabel(item.stepLabel, size = SbControlSize.Small) },
    trailing = { _ -> InboxTime(shortAgo(item.control.updatedAt, now)) },
  )
}

@Composable
internal fun InboxChatRow(row: InboxItem.Chat, now: Instant, selected: Boolean, shortcut: Int?, divider: Boolean, onResume: (ChatHistoryEntry) -> Unit, onDiscard: (ChatHistoryEntry) -> Unit) {
  val colors = SkillBillTheme.colors
  InboxRow(
    onClick = { onResume(row.entry) },
    selected = selected,
    shortcut = shortcut,
    divider = divider,
    leading = { InboxGlyph(if (row.title.imageName != null) InboxGlyphKind.Image else InboxGlyphKind.Chat, if (row.title.imageName != null) colors.data2 else colors.fgFaint) },
    middle = { highlighted -> InboxTitle(row.title.title, row.title.imageName, monoSecondary = true, highlighted = highlighted) },
    badge = row.repoLabel?.let { label ->
      { SbLabel(label, style = SbLabelStyle.Dot(colors.dataColorFor(label)), size = SbControlSize.Small) }
    },
    trailing = { highlighted ->
      if (highlighted) InboxDiscard(row.title.title) { onDiscard(row.entry) }
      else InboxTime(shortAgo(row.entry.updatedAt, now))
    },
  )
}

@Composable
private fun InboxTitle(title: String, secondary: String?, monoSecondary: Boolean, highlighted: Boolean) {
  val colors = SkillBillTheme.colors
  Layout(
    content = {
      BasicText(title, style = inboxTextStyle().copy(color = if (highlighted) colors.fg else colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (secondary != null) BasicText(secondary, style = inboxTextStyle(12.sp, mono = monoSecondary).copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    },
  ) { measurables, constraints ->
    val primary = measurables.first()
    val primaryWidth = primary.maxIntrinsicWidth(constraints.maxHeight)
    val suffix = measurables.getOrNull(1)
    val suffixWidth = suffix?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
    val gap = if (suffix != null) min(8.dp.roundToPx(), constraints.maxWidth) else 0
    val available = (constraints.maxWidth - gap).coerceAtLeast(0)
    val overflow = (primaryWidth.toLong() + suffixWidth - available).coerceAtLeast(0)
    val suffixShrink = if (suffixWidth == 0) 0 else (overflow * (100.0 * suffixWidth / (primaryWidth + 100.0 * suffixWidth))).roundToInt()
    val measuredSuffixWidth = min(suffixWidth, (suffixWidth - suffixShrink).coerceIn(0, available))
    val measuredPrimaryWidth = min(primaryWidth, available - measuredSuffixWidth)
    val primaryPlaceable = primary.measure(Constraints(maxWidth = measuredPrimaryWidth, maxHeight = constraints.maxHeight))
    val suffixPlaceable = suffix?.measure(Constraints(maxWidth = measuredSuffixWidth, maxHeight = constraints.maxHeight))
    val baseline = max(primaryPlaceable[FirstBaseline], suffixPlaceable?.get(FirstBaseline) ?: 0)
    val primaryY = baseline - primaryPlaceable[FirstBaseline]
    val suffixY = baseline - (suffixPlaceable?.get(FirstBaseline) ?: baseline)
    val height = max(primaryY + primaryPlaceable.height, suffixY + (suffixPlaceable?.height ?: 0))
    layout(constraints.maxWidth, constraints.constrainHeight(height)) {
      primaryPlaceable.placeRelative(0, primaryY)
      suffixPlaceable?.placeRelative(primaryPlaceable.width + gap, suffixY)
    }
  }
}

@Composable
private fun InboxTime(text: String) {
  BasicText(text, style = inboxTextStyle(11.5.sp).copy(color = SkillBillTheme.colors.fgFaint, fontFeatureSettings = "cv11, ss01, tnum"), maxLines = 1)
}

@Composable
private fun InboxDiscard(title: String, onDiscard: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  Box(
    Modifier.offset(x = 4.dp).size(22.dp).focusProperties { canFocus = false }
      .then(if (hovered) Modifier.background(colors.bgActive, RoundedCornerShape(4.dp)) else Modifier)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onDiscard)
      .semantics { contentDescription = "Discard $title" },
    contentAlignment = Alignment.Center,
  ) {
    InboxGlyph(InboxGlyphKind.Close, if (hovered) colors.stFailed else colors.fgMuted)
  }
}

@Composable
private fun InboxRow(
  onClick: () -> Unit,
  selected: Boolean,
  shortcut: Int?,
  divider: Boolean,
  leading: @Composable () -> Unit,
  middle: @Composable (highlighted: Boolean) -> Unit,
  trailing: @Composable (highlighted: Boolean) -> Unit,
  blocked: Boolean = false,
  prefix: (@Composable () -> Unit)? = null,
  badge: (@Composable () -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val highlighted = hovered || selected
  Row(
    Modifier.fillMaxWidth().height(34.dp).focusProperties { canFocus = false }
      .drawBehind {
        if (blocked) {
          val end = if (highlighted) Color.White.copy(alpha = .03f) else Color.Transparent
          drawRect(Brush.horizontalGradient(listOf(colors.stBlocked.copy(alpha = if (highlighted) .11f else .07f), end), endX = size.width * .4f))
        } else if (highlighted) drawRect(colors.bgHover)
        if (divider) drawRect(colors.borderSubtle, size = Size(size.width, 1.dp.toPx()))
        if (blocked) drawRect(colors.stBlocked, size = Size(2.dp.toPx(), size.height))
        else if (selected) drawRoundRect(colors.accent, topLeft = Offset(0f, 7.dp.toPx()), size = Size(2.dp.toPx(), (size.height - 14.dp.toPx()).coerceAtLeast(0f)), cornerRadius = CornerRadius(2.dp.toPx()))
      }
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(start = 12.dp, end = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
    prefix?.invoke()
    Box(Modifier.weight(1f)) { middle(highlighted) }
    badge?.invoke()
    Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) { trailing(highlighted) }
    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
      if (shortcut != null) BasicText(shortcut.toString(), style = inboxTextStyle(10.sp, mono = true).copy(color = colors.fgFaint))
    }
  }
}
