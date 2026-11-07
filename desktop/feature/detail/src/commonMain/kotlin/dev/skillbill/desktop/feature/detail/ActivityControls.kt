package dev.skillbill.desktop.feature.detail

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbDivider
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import kotlin.time.Instant

@Composable
internal fun ActivityText(
  text: String,
  modifier: Modifier = Modifier,
  size: Float = 11f,
  weight: FontWeight = FontWeight.Normal,
  faint: Boolean = false,
  sans: Boolean = false,
  color: Color = if (faint) SkillBillTheme.colors.fgFaint else SkillBillTheme.colors.fgMuted,
  maxLines: Int = 1,
  onTextLayout: (TextLayoutResult) -> Unit = {},
) {
  BasicText(text, modifier, style = if (sans) changesSans(size, weight, color, size * 1.4f) else changesMono(size, weight, color, size * 1.4f),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
}

@Composable
internal fun ActivityAction(label: String, onClick: () -> Unit) {
  val source = remember { MutableInteractionSource() }
  ActivityText(label, Modifier.sbFocusRing(source, RoundedCornerShape(3.dp)).clickable(source, null, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
    size = 11.5f, sans = true, weight = FontWeight.Medium, color = SkillBillTheme.colors.accentText)
}

@Composable
internal fun ActivityFilter(blocksOnly: Boolean, count: Int, onChange: (Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  Row(Modifier.border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp))) {
    listOf(false to "All", true to "Blocks $count").forEach { (blocks, label) ->
      val source = remember { MutableInteractionSource() }
      val active = blocksOnly == blocks
      Box(Modifier.sbFocusRing(source, RoundedCornerShape(5.dp))
        .background(if (active) colors.bgOverlay else Color.Transparent, RoundedCornerShape(5.dp))
        .semantics { selected = active }.clickable(source, null, role = Role.Tab, onClick = { onChange(blocks) }).padding(horizontal = 9.dp, vertical = 4.dp)) {
        ActivityText(label, sans = true, size = 11.5f, color = if (active) colors.fg else colors.fgMuted)
      }
    }
  }
}

@Composable
internal fun ActivityTime(at: Instant?, now: Instant, modifier: Modifier = Modifier) {
  Box(modifier.width(40.dp)) {
    if (at != null) SbTooltip("${activityDuration((now - at).inWholeMilliseconds)} ago") {
      ActivityText(formatInstant(at, "HH:mm"), faint = true)
    }
  }
}

@Composable
internal fun activityPulse(running: Boolean): Float {
  if (!running || LocalReducedMotion.current) return 1f
  val animation = rememberInfiniteTransition()
  val alpha by animation.animateFloat(1f, .35f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
  return alpha
}

@Composable
internal fun ActivityDot(status: ActivityStatus, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val color = when (status) {
    ActivityStatus.RUNNING -> colors.accent
    ActivityStatus.BLOCKED -> colors.stBlocked
    ActivityStatus.COMPLETE -> colors.stDone
    else -> colors.fgFaint
  }
  StatusDot(color, modifier, hollow = status == ActivityStatus.PENDING, pulse = status == ActivityStatus.RUNNING)
}

@Composable
internal fun ActivityWait(milliseconds: Long) {
  Row(Modifier.fillMaxWidth().padding(start = 60.dp, top = 7.dp, bottom = 7.dp)) {
    ActivityText("idle ${activityDuration(milliseconds)}", faint = true)
    SbDivider(Modifier.weight(1f).padding(start = 10.dp, top = 7.dp), color = SkillBillTheme.colors.border)
  }
}
