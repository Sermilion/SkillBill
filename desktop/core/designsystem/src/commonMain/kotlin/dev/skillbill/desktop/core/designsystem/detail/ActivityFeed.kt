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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.tabular
import kotlin.time.Duration
import kotlin.time.Instant

/** How many entries an [ActivityFeed] shows before the earlier ones fold. */
const val ACTIVITY_FEED_MAX = 8

/**
 * One activity line: a 16 px [lead] (a small Avatar or a StatusIcon), then "[actor] [action] [target]" and the age of [at].
 * [monoTarget] draws the target in 12 px mono, for paths and runtime codes. [tooltip] replaces the full text in the tooltip.
 */
@Immutable
class ActivityFeedEntry(
  val lead: @Composable () -> Unit,
  val actor: String?,
  val action: String?,
  val target: String? = null,
  val monoTarget: Boolean = false,
  val at: Instant?,
  val tooltip: String? = null,
)

/** "12s ago", "4m ago", "3h ago", "2d ago". */
fun relativeAge(elapsed: Duration): String {
  val seconds = elapsed.inWholeSeconds.coerceAtLeast(0)
  return when {
    seconds < 60 -> "${seconds}s ago"
    seconds < 3_600 -> "${seconds / 60}m ago"
    seconds < 86_400 -> "${seconds / 3_600}h ago"
    else -> "${seconds / 86_400}d ago"
  }
}

/**
 * `.sb-feed`: one 28 px line per entry in the given order, the icons joined by a 1 px `border-subtle` connector from the first
 * icon's center to the last's. Past [ACTIVITY_FEED_MAX] entries the earlier ones fold behind "Show N earlier" at the top until
 * [showAll]. Ages tick with the window's UI ticker.
 */
@Composable
fun ActivityFeed(entries: List<ActivityFeedEntry>, showAll: Boolean, onShowEarlier: () -> Unit, modifier: Modifier = Modifier) {
  val hidden = if (showAll) 0 else (entries.size - ACTIVITY_FEED_MAX).coerceAtLeast(0)
  val shown = entries.drop(hidden)
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val now = ticker.now.value
  val connector = SkillBillTheme.colors.borderSubtle
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    if (hidden > 0) ShowEarlier(hidden, onShowEarlier)
    Column(
      Modifier.fillMaxWidth().drawBehind {
        if (shown.size > 1) {
          val half = SkillBillSize.activityRow.toPx() / 2
          val x = 8.dp.toPx()
          drawLine(connector, Offset(x, half), Offset(x, size.height - half), 1.dp.toPx())
        }
      },
    ) {
      shown.forEach { entry -> FeedRow(entry, entry.at?.let { relativeAge(now - it) }) }
    }
  }
}

@Composable
private fun FeedRow(entry: ActivityFeedEntry, ago: String?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val text = buildAnnotatedString {
    val strong = SpanStyle(color = colors.fg2, fontWeight = FontWeight.Medium)
    var first = true
    fun gap() { if (!first) append(" "); first = false }
    entry.actor?.takeIf { it.isNotBlank() }?.let { gap(); withStyle(strong) { append(it) } }
    entry.action?.takeIf { it.isNotBlank() }?.let { gap(); append(it) }
    entry.target?.takeIf { it.isNotBlank() }?.let { target ->
      gap()
      val style = if (entry.monoTarget) {
        SpanStyle(color = colors.fg2, fontFamily = type.monoSm.fontFamily, fontSize = type.monoSm.fontSize, fontWeight = FontWeight.Normal)
      } else {
        strong
      }
      withStyle(style) { append(target) }
    }
  }
  val full = entry.tooltip ?: listOfNotNull(entry.actor, entry.action, entry.target).filter { it.isNotBlank() }.joinToString(" ")
  Row(Modifier.fillMaxWidth().height(SkillBillSize.activityRow), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(16.dp).background(colors.bgPanel, CircleShape), contentAlignment = Alignment.Center) { entry.lead() }
    Spacer(Modifier.width(10.dp))
    Box(Modifier.weight(1f, fill = false)) {
      SbTooltip(full) {
        BasicText(text, style = type.tBase.copy(color = colors.fgMuted), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }
    }
    ago?.let {
      Spacer(Modifier.width(4.dp))
      BasicText("· $it", style = type.tSm.tabular().copy(color = colors.fgFaint), maxLines = 1, softWrap = false)
    }
  }
}

/** `.sb-feed-more`: a 24 px ghost toggle, chevronUp and "Show N earlier" in 12 px `fg-faint`, pulled 6 px left. */
@Composable
private fun ShowEarlier(count: Int, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  val color = if (hovered) colors.fgMuted else colors.fgFaint
  Row(
    Modifier.pullStart(6).height(24.dp)
      .background(if (hovered) colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
      .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
      .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides color) { SbIcon(LucideIcons.chevronUp, SbIconSize.Sm) }
    BasicText("Show $count earlier", style = SkillBillTheme.type.tSm.copy(color = color), maxLines = 1)
  }
}
