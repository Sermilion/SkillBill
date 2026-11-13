package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

internal fun planWaveLabel(plan: StandalonePlanProgress): String? {
  val wave = plan.planningWaveNumber ?: return null
  val capacity = plan.planningWaveCapacity?.takeIf { it > 0 } ?: return "wave $wave"
  val remaining = plan.units.count { it.state == "queued" }
  if (hasBlockedSubtasks(plan) && remaining > 0) return "wave ${wave + 1} waits"
  val total = wave + (remaining + capacity - 1) / capacity
  return "wave $wave of $total"
}

@Composable
internal fun PlanSubtaskRow(unit: StandalonePlanUnit, item: WorkItem, detail: Detail, modifier: Modifier, onOpen: () -> Unit) {
  val ticker = LocalUiTicker.current
  val liveWriting = unit.state == "writing" && item.running
  if (liveWriting) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = if (item.running) ticker.now.value else detail.updatedAt
  val quietSince = if (liveWriting) planUnitQuietSince(unit, now) else null
  val saved = unit.state == "saved"
  val blocked = unit.state == "blocked"
  val colors = SkillBillTheme.colors
  val state = when {
    saved -> {
      val started = unit.startedAt
      val finished = unit.finishedAt
      if (started != null && finished != null) elapsedText(started, finished) else "Saved"
    }
    blocked -> "Blocked"
    quietSince != null -> "quiet ${elapsedText(quietSince, now)}"
    liveWriting -> unit.startedAt?.let { "Writing · ${elapsedText(it, now)}" } ?: "Writing"
    hasBlockedSubtasks(detail.standalonePlan) -> "Not started"
    else -> "Queued"
  }
  val stateColor = when {
    blocked || quietSince != null -> colors.stBlocked
    liveWriting -> colors.stActive
    else -> colors.fgFaint
  }
  val background = when {
    blocked -> colors.stBlocked.copy(alpha = .05f)
    liveWriting && quietSince == null -> colors.stActive.copy(alpha = .03f)
    else -> Color.Transparent
  }
  val mutedTitle = !saved && !blocked && !liveWriting
  val second = when {
    blocked -> unit.blockedReason?.takeIf(String::isNotBlank)
    liveWriting && quietSince == null -> planWritingLine(unit, item, detail)
    else -> null
  }
  Row(
    modifier.fillMaxWidth().background(background)
      .then(if (saved) Modifier.clickable(role = Role.Button, onClickLabel = "Open saved plan", onClick = onOpen) else Modifier)
      .semantics { stateDescription = state }
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PlanUnitGlyph(saved, blocked, liveWriting, quietSince != null)
    BasicText("${unit.subtaskId}", style = SkillBillTheme.type.tXs.copy(fontFamily = SkillBillTheme.type.t2xs.fontFamily, color = colors.fgFaint), maxLines = 1)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      BasicText(
        unit.name,
        style = SkillBillTheme.type.tSm.copy(color = if (mutedTitle) colors.fgMuted else colors.fg, fontWeight = if (mutedTitle) FontWeight.Normal else FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (second != null) BasicText(
        second,
        style = if (blocked) SkillBillTheme.type.tXs.copy(color = colors.stBlocked) else SkillBillTheme.type.t2xs.copy(color = colors.fgMuted),
        maxLines = if (blocked) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(state, style = SkillBillTheme.type.tXs.copy(color = stateColor), maxLines = 1)
      if (saved) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.external, 12.dp) }
    }
  }
}

@Composable
private fun PlanUnitGlyph(saved: Boolean, blocked: Boolean, liveWriting: Boolean, quiet: Boolean) {
  val colors = SkillBillTheme.colors
  Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
    when {
      saved -> CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, 12.dp) }
      blocked -> CompositionLocalProvider(LocalContentColor provides colors.stBlocked) { SbIcon(LucideIcons.warn, 12.dp) }
      liveWriting && quiet -> SbSpinner(11.dp, colors.stBlocked, modifier = Modifier.rotate(-90f), durationMillis = 2_400, trackAlpha = .25f, arcSweep = 90f)
      liveWriting -> SbSpinner(11.dp, colors.stActive, modifier = Modifier.rotate(-90f), durationMillis = 900, trackAlpha = .25f, arcSweep = 90f)
      else -> PlanDashedRing(colors.fgFaint)
    }
  }
}

@Composable
internal fun PlanDashedRing(color: Color) {
  Canvas(Modifier.size(11.dp)) {
    drawCircle(color, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
  }
}

private fun planWritingLine(unit: StandalonePlanUnit, item: WorkItem, detail: Detail): String? {
  if (detail.planActivity.none { it.kind != "stream" }) return null
  val at = unit.latestOutputAt
  val started = unit.startedAt
  if (at != null && started != null && at < started) return null
  val raw = unit.latestOutput ?: detail.planSubtaskLatestLines[unit.subtaskId] ?: return null
  return planOutputText(raw, planRoots(item)).display.takeIf(String::isNotBlank)
}

internal fun planUnitQuietSince(unit: StandalonePlanUnit, now: Instant): Instant? {
  if (unit.state != "writing") return null
  val latest = unit.latestOutputAt?.takeIf { unit.startedAt?.let { started -> it >= started } != false } ?: unit.startedAt ?: return null
  return latest.takeIf { (now - it).inWholeSeconds >= PLAN_QUIET_SECONDS }
}

internal fun elapsedTextOrNull(start: Instant?, end: Instant): String? = start?.let { elapsedText(it, end) }

internal fun elapsedText(start: Instant, end: Instant): String {
  val seconds = ((end - start).inWholeSeconds).coerceAtLeast(0)
  return when {
    seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    else -> "${seconds}s"
  }
}
