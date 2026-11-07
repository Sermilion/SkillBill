package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityPhase
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import kotlin.time.Instant

@Composable
internal fun ActivityStats(groups: List<ActivityGroup>, now: Instant) {
  val colors = SkillBillTheme.colors
  val waiting = remember(groups, now) { activityIdleMilliseconds(groups, now) }
  val phases = groups.flatMap { it.phases }
  val models = activityModelLabels(phases, now)
  val subtasks = groups.filter { it.subtaskId != null }.groupBy { it.subtaskId }
  val completed = subtasks.values.count { attempts -> attempts.last().status == ActivityStatus.COMPLETE }
  val blocks = groups.sumOf { it.blocks.size }
  val blockedSubtasks = subtasks.values.count { attempts -> attempts.any { it.blocks.isNotEmpty() } }
  val failedPrs = groups.flatMap { it.blocks }.count { it.kind == dev.skillbill.desktop.core.domain.model.ActivityBlockKind.PULL_REQUEST_FAILED }
  val blockDetail = listOfNotNull(
    blockedSubtasks.takeIf { it > 0 }?.let { "$it subtask${if (it == 1) "" else "s"}" },
    failedPrs.takeIf { it > 0 }?.let { "$it PR${if (it == 1) "" else "s"}" },
  ).joinToString(", ")
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.border).border(1.dp, colors.border, RoundedCornerShape(8.dp)), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
    ActivityStat("Phase time", activityDuration(groups.sumOf { it.activeMilliseconds(now) }), if (waiting > 0) "${activityDuration(waiting)} idle" else "no long gaps", Modifier.weight(1f))
    ActivityStat("Blocks", blocks.toString(), if (blocks > 0) blockDetail.ifBlank { "stopped for help" } else "none", Modifier.weight(1f), warning = blocks > 0)
    ActivityStat(if (subtasks.isEmpty()) "Runs" else "Subtasks", if (subtasks.isEmpty()) phases.size.toString() else "$completed of ${subtasks.size}",
      "${phases.size} phase${if (phases.size == 1) "" else "s"} · ${phases.count { it.phaseId.contains("fix") }} fixes", Modifier.weight(1f))
    ActivityStat("Models", models.firstOrNull() ?: "none", if (models.size > 1) "+ ${models.drop(1).joinToString(", ")}" else if (models.size == 1) "only model" else "", Modifier.weight(1f))
  }
}

internal fun activityModelLabels(phases: List<ActivityPhase>, now: Instant): List<String> {
  val labeled = phases.mapNotNull { phase ->
    phase.model?.removePrefix("claude-")?.takeIf(String::isNotEmpty)?.let { label -> label to phase }
  }
  val byDuration = labeled.groupBy({ it.first }, { it.second }).entries.sortedByDescending { (_, owned) ->
    owned.sumOf { phase -> phase.startedAt?.let { start -> ((phase.finishedAt ?: now) - start).inWholeMilliseconds } ?: 0L }
  }.map { it.key }
  val current = labeled.filter { it.second.finishedAt == null }
    .maxByOrNull { it.second.startedAt ?: Instant.DISTANT_PAST }
    ?.first
  return if (current == null) byDuration else listOf(current) + byDuration.filterNot { it == current }
}

@Composable
private fun ActivityStat(label: String, value: String, detail: String, modifier: Modifier, warning: Boolean = false) {
  Column(modifier.background(SkillBillTheme.colors.bgRaised).padding(horizontal = 12.dp, vertical = 10.dp)) {
    ActivityText(label.uppercase(), size = 10f, weight = FontWeight.Medium, faint = true)
    SbTooltip(value) { ActivityText(value, Modifier.padding(top = 6.dp), size = 15f, weight = FontWeight.SemiBold, sans = true, color = if (warning) SkillBillTheme.colors.stBlocked else SkillBillTheme.colors.fg) }
    SbTooltip(detail) { ActivityText(detail, Modifier.padding(top = 4.dp)) }
  }
}

@Composable
internal fun ActivityBar(groups: List<ActivityGroup>, now: Instant, onGroup: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val spans = remember(groups, now) { activitySpans(groups, now) }
  if (spans.isEmpty()) return
  val maxWork = spans.filter { it.groupId != null && !it.block && !it.running }.maxOfOrNull { it.milliseconds.coerceAtLeast(60_000L) } ?: 60_000L
  Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(22.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    spans.forEach { span ->
      val group = groups.firstOrNull { it.id == span.groupId }
      val label = when {
        span.groupId == null -> "idle ${activityDuration(span.milliseconds)}"
        span.block -> "Blocked ${formatInstant(span.start, "HH:mm")}"
        span.running -> "Running now"
        else -> "${group?.name.orEmpty()} · ${activityDuration(span.milliseconds)}"
      }
      val width = when {
        span.groupId == null -> Modifier.width(34.dp)
        span.block -> Modifier.width(4.dp)
        span.running -> Modifier.width(8.dp)
        else -> Modifier.weight((span.milliseconds.coerceAtLeast(60_000L).toFloat() / maxWork).coerceAtLeast(.001f))
      }
      Box(width.height(22.dp)) {
        SbTooltip(label) {
          val color = when {
            span.block -> colors.stBlocked
            span.running -> colors.accent
            group?.subtaskId == null -> colors.data2.copy(alpha = .6f)
            else -> colors.stripDone
          }
          Box(Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)).alpha(activityPulse(span.running))
            .then(if (span.groupId != null) Modifier.background(color).clickable(role = Role.Button, onClickLabel = "Jump to ${group?.name.orEmpty()}") { onGroup(span.groupId) } else Modifier)) {
            if (span.groupId == null) Canvas(Modifier.fillMaxSize()) {
              val gap = 6.dp.toPx()
              var x = -size.height
              while (x < size.width) {
                drawLine(colors.stripIdle, Offset(x, size.height), Offset(x + size.height, 0f), 3.dp.toPx())
                x += gap
              }
            }
          }
        }
      }
    }
  }
  Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    ActivityText(formatInstant(spans.first().start, "MMM d HH:mm"), size = 10f, faint = true)
    Spacer(Modifier.weight(1f))
    ActivityText(if (groups.any { it.status == ActivityStatus.RUNNING }) "now" else formatInstant(spans.last().end, "MMM d HH:mm"), size = 10f, faint = true)
  }
}
