package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.MetricCell
import dev.skillbill.desktop.core.designsystem.detail.MetricStrip
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

private data class PlanStageLabel(val title: String, val id: String)

private val PLAN_STAGES = listOf(
  PlanStageLabel("Reading the repo", "preplan"),
  PlanStageLabel("Splitting the work", "plan"),
  PlanStageLabel("Writing subtask specs", "plan_subtasks"),
)

internal fun DetailSections.planStatus(item: WorkItem, detail: Detail) {
  this.item(key = "plan-stage-strip") {
    PlanStageStripContent(item, detail)
  }
  this.item(key = "plan-status") {
    PlanStatusLine(item, detail)
  }
  this.item(key = "plan-heartbeat") {
    PlanHeartbeat(item, detail, planQuietSince(item, detail))
  }
}

@Composable
private fun PlanStageStripContent(item: WorkItem, detail: Detail) {
  val plan = detail.standalonePlan
  val stages = plan?.stages.orEmpty().associateBy(StandalonePlanStage::phaseId)
  val ticker = LocalUiTicker.current
  if (item.running) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = if (item.running) ticker.now.value else detail.observedAt
  val cells = PLAN_STAGES.map { label ->
    val stage = stages[label.id]
    val blocked = stage?.status == "blocked" || (label.id == "plan_subtasks" && hasBlockedSubtasks(plan))
    val quiet = stage?.status == "running" && planQuietSince(item, detail) != null
    val status = when {
      blocked -> "blocked"
      stage?.status == "running" && item.running -> "active"
      stage?.status == "running" -> "paused"
      stage?.status == "completed" -> "done"
      stage?.status == "failed" -> "failed"
      stage?.status == "paused" -> "paused"
      else -> "queued"
    }
    val duration = when {
      stage?.status == "running" -> elapsedTextOrNull(stage.firstStartedAt, now)
      stage?.finishedAt != null -> stage.finishedAt?.let { elapsedTextOrNull(stage.firstStartedAt, it) }
      !item.running -> elapsedTextOrNull(stage?.firstStartedAt, detail.updatedAt)
      else -> null
    }
    val statusColor = when {
      blocked || quiet || status == "failed" -> SkillBillTheme.colors.stBlocked
      status == "active" -> SkillBillTheme.colors.stActive
      status == "done" -> SkillBillTheme.colors.fg2
      else -> SkillBillTheme.colors.fgFaint
    }
    MetricCell(
      label = label.title,
      value = duration.orEmpty(),
      note = label.id,
      lead = { PlanGlyph(if (blocked) "blocked" else if (status == "done") "saved" else if (status == "active") "writing" else "queued", statusColor) },
      tone = when { blocked || quiet -> SkillBillTheme.colors.stBlocked; status == "active" -> SkillBillTheme.colors.stActive; else -> null },
    )
  }
  MetricStrip(cells, Modifier.padding(top = DocBlockGap))
}

@Composable
private fun PlanStatusLine(item: WorkItem, detail: Detail) {
  BasicText(planStatusText(item, detail), Modifier.fillMaxWidth().padding(top = 4.dp), style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
}

internal fun planStatusText(item: WorkItem, detail: Detail): String {
  val plan = detail.standalonePlan
  return when {
    hasBlockedSubtasks(plan) -> "${blockedSubtasksLabel(blockedSubtaskIds(plan))}. ${plan?.plannedSubtaskCount} of ${plan?.totalSubtaskCount} saved, ${plan?.units?.count { it.state == "queued" }} not started."
    planFinished(item, detail) -> "Plan ready. ${plan?.totalSubtaskCount ?: 0} subtasks, all specs saved."
    !item.running -> lifecycleLabel(item.lifecycle)
    plan?.units?.isNotEmpty() == true -> {
      val writing = plan.units.filter { it.state == "writing" }.map { it.subtaskId }
      val action = if (writing.isEmpty()) "Preparing the next subtask specs" else "Writing specs for ${if (writing.size == 1) "subtask" else "subtasks"} ${writing.joinToString(", ")}"
      "$action. ${plan.plannedSubtaskCount} of ${plan.totalSubtaskCount} saved."
    }
    plan?.stages?.firstOrNull { it.status == "running" }?.phaseId == "plan" -> "Splitting the work into subtasks. The list appears here once the decomposition is saved."
    else -> "Reading the repository and the intake before it splits the work."
  }
}

internal fun hasBlockedSubtasks(plan: StandalonePlanProgress?): Boolean = blockedSubtaskIds(plan).isNotEmpty()

internal fun blockedSubtaskIds(plan: StandalonePlanProgress?): List<Int> =
  plan?.blockedSubtaskIds.orEmpty().ifEmpty { plan?.blockedSubtaskId?.let(::listOf).orEmpty() }

internal fun blockedSubtasksLabel(ids: List<Int>): String =
  "Planning stopped at ${if (ids.size == 1) "subtask" else "subtasks"} ${ids.joinToString(", ")}"

@Composable
private fun PlanHeartbeat(item: WorkItem, detail: Detail, stalledSince: Instant?) {
  val ticker = LocalUiTicker.current
  val outputAt = detail.planOutputUpdatedAt
  val elapsedOnly = item.running && item.activeDurationAsOf == null && item.startedAt != null
  if (item.running && (elapsedOnly || stalledSince != null || outputAt != null)) {
    DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  }
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(if (planFinished(item, detail)) "Total" else "Elapsed", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    if (elapsedOnly) {
      BasicText(elapsedTextOrNull(item.startedAt, ticker.now.value).orEmpty(), style = SkillBillTheme.type.tSm.copy(color = colors.fg))
    } else {
      SbClock(item.activeDurationMs, item.activeDurationAsOf, live = item.running, showSeconds = true)
    }
    if (item.running && stalledSince != null) {
      BasicText("No output for ${elapsedText(stalledSince, ticker.now.value)}", style = SkillBillTheme.type.tSmMedium.copy(color = colors.stBlocked))
    } else if (item.running && outputAt != null) {
      val age = elapsedText(outputAt, ticker.now.value)
      BasicText("Last output $age ago", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    }
    if (planFinished(item, detail)) item.finishedAt?.let {
      BasicText("finished ${formatInstant(it, "d MMM HH:mm")}", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    }

  }
}


internal const val PLAN_QUIET_SECONDS = 5 * 60L

internal fun planQuietSince(item: WorkItem, detail: Detail): Instant? {
  if (!item.running) return null
  val latest = detail.planOutputUpdatedAt ?: item.startedAt ?: return null
  return latest.takeIf { (detail.observedAt - it).inWholeSeconds >= PLAN_QUIET_SECONDS }
}
