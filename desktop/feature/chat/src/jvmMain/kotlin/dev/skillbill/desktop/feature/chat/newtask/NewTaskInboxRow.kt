package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbMarqueeText
import dev.skillbill.desktop.core.designsystem.component.dataColorFor
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
internal fun InboxRunRow(item: WorkItem, now: Instant, divider: Boolean, selected: Boolean, onOpenRun: (WorkItem) -> Unit) {
  val colors = SkillBillTheme.colors
  InboxRow(
    onClick = { onOpenRun(item) },
    blocked = true,
    divider = divider,
    selected = selected,
    leading = { InboxGlyph(InboxGlyphKind.Blocked, colors.stBlocked) },
    prefix = { BasicText(item.issueKey ?: item.id, style = inboxTextStyle(11.5.sp, mono = true).copy(color = colors.fgMuted), maxLines = 1) },
    middle = { highlighted, _ -> InboxTitle(item.title, item.exactPauseReason ?: item.reason ?: item.summary.takeIf(String::isNotBlank), monoSecondary = false, highlighted = highlighted) },
    badge = { InboxBadge(item.stepLabel) },
    trailing = { _ -> InboxTime(shortAgo(item.control.updatedAt, now)) },
  )
}

@Composable
internal fun InboxChatRow(entry: ChatHistoryEntry, selectedRoot: String?, now: Instant, divider: Boolean, open: Boolean, selected: Boolean, onResume: (ChatHistoryEntry) -> Unit, onDiscard: (ChatHistoryEntry) -> Unit) {
  val colors = SkillBillTheme.colors
  val title = remember(entry.title, entry.summary) { chatTitle(entry.title) }
  val displayTitle = entry.summary ?: title.title
  val repoLabel = chatRepoLabel(entry, selectedRoot)
  InboxRow(
    onClick = { onResume(entry) },
    divider = divider,
    selected = selected,
    leading = { InboxGlyph(if (title.imageName != null) InboxGlyphKind.Image else InboxGlyphKind.Chat, if (title.imageName != null) colors.data2 else colors.fgFaint) },
    middle = { highlighted, hovered -> InboxTitle(displayTitle, title.imageName, monoSecondary = true, highlighted = highlighted, marquee = entry.summary != null && hovered) },
    badge = if (open || repoLabel != null) {
      {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          if (open) InboxBadge("open", textColor = colors.accentText, borderColor = colors.accentRing)
          repoLabel?.let { InboxBadge(it, colors.dataColorFor(it)) }
        }
      }
    } else null,
    trailing = { highlighted ->
      if (highlighted && !open) InboxDiscard(displayTitle) { onDiscard(entry) }
      else InboxTime(shortAgo(entry.updatedAt, now))
    },
  )
}

@Composable
private fun InboxBadge(text: String, swatch: Color? = null, textColor: Color? = null, borderColor: Color? = null) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.height(18.dp).border(1.dp, borderColor ?: colors.border, RoundedCornerShape(50)).padding(horizontal = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (swatch != null) Box(Modifier.size(6.dp).background(swatch, RoundedCornerShape(2.dp)))
    BasicText(text, style = inboxTextStyle(10.5.sp).copy(color = textColor ?: colors.fgMuted), maxLines = 1)
  }
}

@Composable
private fun InboxTitle(title: String, secondary: String?, monoSecondary: Boolean, highlighted: Boolean, marquee: Boolean = false) {
  val colors = SkillBillTheme.colors
  Layout(
    content = {
      val style = inboxTextStyle().copy(color = if (highlighted) colors.fg else colors.fg2)
      if (marquee) SbMarqueeText(title, style) else BasicText(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
  val shape = RoundedCornerShape(4.dp)
  Box(
    Modifier.offset(x = 4.dp).size(22.dp)
      .then(if (hovered) Modifier.background(colors.bgActive, shape) else Modifier)
      .focusProperties { canFocus = false }
      .clickable(interaction, indication = null, role = Role.Button, onClick = onDiscard)
      .sbFocusRing(interaction, shape)
      .semantics { contentDescription = "Discard $title" },
    contentAlignment = Alignment.Center,
  ) {
    InboxGlyph(InboxGlyphKind.Close, if (hovered) colors.stFailed else colors.fgMuted)
  }
}

@Composable
private fun InboxRow(
  onClick: () -> Unit,
  divider: Boolean,
  leading: @Composable () -> Unit,
  middle: @Composable (highlighted: Boolean, hovered: Boolean) -> Unit,
  trailing: @Composable (highlighted: Boolean) -> Unit,
  blocked: Boolean = false,
  selected: Boolean = false,
  prefix: (@Composable () -> Unit)? = null,
  badge: (@Composable () -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  var focusedWithin by remember { mutableStateOf(false) }
  val highlighted = hovered || focusedWithin || selected
  Row(
    Modifier.fillMaxWidth().height(34.dp)
      .drawBehind {
        if (blocked) {
          val end = if (highlighted) Color.White.copy(alpha = .03f) else Color.Transparent
          drawRect(Brush.horizontalGradient(listOf(colors.stBlocked.copy(alpha = if (highlighted) .11f else .07f), end), endX = size.width * .4f))
        } else if (highlighted) drawRect(colors.bgHover)
        if (selected && blocked) drawRect(colors.bgHover)
        if (selected && !blocked) {
          drawRect(colors.accent, topLeft = androidx.compose.ui.geometry.Offset(7.dp.toPx(), 7.dp.toPx()), size = Size(2.dp.toPx(), size.height - 14.dp.toPx()))
        }
        if (divider) drawRect(colors.borderSubtle, size = Size(size.width, 1.dp.toPx()))
        if (blocked) drawRect(colors.stBlocked, size = Size(2.dp.toPx(), size.height))
      }
      .pointerHoverIcon(PointerIcon.Hand)
      .onFocusChanged { focusedWithin = it.hasFocus }
      .focusProperties { canFocus = false }
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .sbFocusRing(interaction, RectangleShape)
      .padding(start = 12.dp, end = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { leading() }
    prefix?.invoke()
    Box(Modifier.weight(1f)) { middle(highlighted, hovered) }
    badge?.invoke()
    Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) { trailing(highlighted) }
  }
}
