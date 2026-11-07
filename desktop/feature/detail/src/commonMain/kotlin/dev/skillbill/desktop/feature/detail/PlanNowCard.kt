package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.sbPulse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun PlanNowBlock(item: WorkItem, detail: Detail) {
  val ticker = LocalUiTicker.current
  val live = item.running
  val startedAt = item.startedAt
  val events = planActivity(item, detail)
  val structured = detail.planActivity.filterNot { it.kind == "stream" }
  val latestStructured = structured.maxByOrNull { it.updatedAt }
  val latest = latestStructured?.let { source -> events.firstOrNull { it.key == source.id && it.phaseId == source.phaseId } }
  val currentPhase = planStageViews(item, detail).lastOrNull { it.state == PlanStageState.Running }?.phaseId
  val currentAgent = currentPhase?.let { phaseId ->
    structured.filter { it.phaseId == phaseId }.maxByOrNull { it.updatedAt }?.agentId
  }?.takeIf { it.isNotBlank() }
  val quietSince = planQuietSince(item, detail)
  val quiet = quietSince != null
  val noStructuredActivity = detail.planActivity.none { it.kind != "stream" }
  if (live) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val colors = SkillBillTheme.colors
  val tint = if (quiet) colors.stBlocked else colors.stActive
  val plan = detail.standalonePlan
  val ready = planFinished(item, detail)
  val status = if (ready) "Plan ready. ${plan?.totalSubtaskCount ?: 0} subtasks, all specs saved." else planStatusText(item, detail)

  Column(
    Modifier.fillMaxWidth().padding(top = SkillBillSpace.s3)
      .then(if (live) Modifier.border(1.dp, tint.copy(alpha = if (quiet) .30f else .22f), RoundedCornerShape(SkillBillRadius.md)) else Modifier)
      .then(if (live) Modifier.background(
        Brush.horizontalGradient(colorStops = arrayOf(0f to tint.copy(alpha = if (quiet) .07f else .06f), .6f to Color.Transparent, 1f to Color.Transparent)),
        RoundedCornerShape(SkillBillRadius.md),
      ) else Modifier)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    BasicText(status, style = SkillBillTheme.type.tMd.copy(color = colors.fg))
    if (live) {
      if (noStructuredActivity) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          NowEventTile(LucideIcons.clock, tint)
          BasicText("This agent does not stream its steps. Saved files appear as they land.", style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
        }
      } else if (latest != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          NowEventTile(if (quiet) LucideIcons.clock else activityIcon(latest), tint)
          BasicText(if (quiet) quietStepText(latest) else nowEventText(latest, item), style = SkillBillTheme.type.tSm.copy(color = colors.fg2), maxLines = 2)
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (quiet) {
          Box(Modifier.size(6.dp).background(colors.stBlocked, CircleShape))
          BasicText("No output for ${elapsedText(requireNotNull(quietSince), ticker.now.value)}", style = SkillBillTheme.type.tSm.copy(color = colors.stBlocked, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
        } else {
          Box(Modifier.size(6.dp).background(colors.stActive, CircleShape).sbPulse())
          (detail.planOutputUpdatedAt ?: latestStructured?.updatedAt)?.let { lastOutput ->
            BasicText("Last output ${elapsedText(lastOutput, ticker.now.value)} ago", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
          }
        }
        BasicText("Elapsed", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
        if (item.activeDurationAsOf == null && startedAt != null) {
          BasicText(elapsedText(startedAt, ticker.now.value), style = SkillBillTheme.type.tSm.copy(color = colors.fg))
        } else CompositionLocalProvider(LocalContentColor provides colors.fg) {
          SbClock(item.activeDurationMs, item.activeDurationAsOf, live = true, showSeconds = true)
        }
        currentAgent?.let { BasicText(it, style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint)) }
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(if (ready) 12.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("Total", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
        CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbClock(item.activeDurationMs, if (ready) null else item.activeDurationAsOf, live = false, showSeconds = true) }
        if (ready) item.finishedAt?.let { finished ->
          val today = formatInstant(detail.observedAt, "yyyy-MM-dd") == formatInstant(finished, "yyyy-MM-dd")
          BasicText("finished ${formatInstant(finished, if (today) "HH:mm" else "d MMM HH:mm")}", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
        }
      }
    }
  }
}

@Composable
private fun NowEventTile(icon: ImageVector, tint: Color) {
  Box(Modifier.size(24.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(SkillBillRadius.sm)), contentAlignment = Alignment.Center) {
    CompositionLocalProvider(LocalContentColor provides tint) { SbIcon(icon, 14.dp) }
  }
}

@Composable
private fun nowEventText(event: PlanActivityEvent, item: WorkItem) = buildAnnotatedString {
  event.subtaskId?.let { withStyle(SpanStyle(color = SkillBillTheme.colors.fgMuted)) { append("#$it · ") } }
  if (event.kind == "note") {
    append(event.text)
  } else {
    val verb = if (event.state == "failed") planActivityVerb(event) else event.verb ?: planActivityVerb(event)
    val subject = if (event.kind in setOf("read", "write")) event.path?.let(::fileName).orEmpty() else event.text
    if (verb.isNotBlank()) append("$verb ")
    withStyle(SpanStyle(color = SkillBillTheme.colors.accentText, fontFamily = FontFamily.Monospace)) { append(subject.ifBlank { planOutputText(event.text, planRoots(item)).subject }) }
  }
}

@Composable
private fun quietStepText(event: PlanActivityEvent) = buildAnnotatedString {
  if (event.subtaskId == null) append("Last step · ")
  else withStyle(SpanStyle(color = SkillBillTheme.colors.fgMuted)) { append("Last step #${event.subtaskId} · ") }
  val verb = (if (event.state == "failed") planActivityVerb(event) else event.verb ?: planActivityVerb(event)).lowercase()
  if (verb.isNotBlank()) append("$verb ")
  val file = event.path?.let(::fileName) ?: event.text
  withStyle(SpanStyle(color = SkillBillTheme.colors.accentText, fontFamily = FontFamily.Monospace)) { append(file) }
  event.updatedAt?.let { append(" at ${formatInstant(it, "HH:mm:ss")}") }
}

private fun activityIcon(event: PlanActivityEvent) = when {
  event.state == "failed" || event.kind == "error" -> LucideIcons.warn
  event.kind == "read" -> LucideIcons.file
  event.kind == "write" -> LucideIcons.pencil
  event.kind == "search" -> LucideIcons.search
  event.kind == "command" -> LucideIcons.terminal
  event.kind == "mcp" -> LucideIcons.plug
  event.kind == "note" -> LucideIcons.messageSquare
  else -> LucideIcons.link
}
