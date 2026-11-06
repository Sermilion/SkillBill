package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

private data class PlanStageLabel(val title: String, val id: String)

private val PLAN_STAGES = listOf(
  PlanStageLabel("Reading the repo", "preplan"),
  PlanStageLabel("Splitting the work", "plan"),
  PlanStageLabel("Writing subtask specs", "plan_subtasks"),
)

internal fun DetailSections.planDocument(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  view: RunActionsView,
  onAction: (DetailAction) -> Unit,
) {
  val plan = detail.standalonePlan
  planStatus(item, detail, plan?.stalledSince)
  plan?.blockedSubtaskId?.let { id ->
    this.item(key = "plan-blocked") {
      Column(Modifier.padding(top = DocBlockGap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Planning stopped at subtask $id", FeedbackTone.Warn, LucideIcons.alert)
        plan.blockedReason?.takeIf(String::isNotBlank)?.let { reason ->
          BasicText(reason, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.stBlocked))
        }
      }
    }
  }
  if (plan?.blockedSubtaskId != null || detail.incident != null || view.decision != null) incident(state, view, onAction)
  if (plan?.units.isNullOrEmpty()) {
    detail.planOutputTail.takeIf { it.isNotEmpty() }?.let { lines ->
      item(key = "plan-output-tail") {
        ReasonBlock("Latest output", lines.joinToString("\n"), onCopy = { onAction(DetailAction.Copy(it)) })
      }
    }
  }
  if (plan != null && plan.units.isNotEmpty()) {
    planRows(
      plan.units, plan.totalSubtaskCount, plan.planningWaveNumber, plan.planningWaveCapacity,
      plan.planningWaveHistoryComplete, detail, onAction,
    )
  }
  planBundle(detail, plan?.units.orEmpty(), onAction)
  val briefings = plan?.stages.orEmpty().mapNotNull { stage -> stage.briefingText?.takeIf(String::isNotBlank)?.let { stage.phaseId to it } }
  if (briefings.isNotEmpty()) section("plan-briefing", "Briefing", collapsedByDefault = true) {
    items(briefings, key = { "plan-briefing-${it.first}" }) { (id, text) -> ReasonBlock("$id briefing", text, onCopy = { onAction(DetailAction.Copy(it)) }) }
  }
  detail.planOutputTail.takeIf { it.isNotEmpty() }?.let { lines ->
    section("plan-agent-output", "Agent output", collapsedByDefault = true) {
      item(key = "plan-agent-output-lines") {
        ReasonBlock("Latest output", lines.joinToString("\n"), onCopy = { onAction(DetailAction.Copy(it)) })
      }
    }
  }
  section("plan-activity", "Activity", collapsedByDefault = true) {
    item(key = "plan-activity-feed") {
      ActivityTimeline(standaloneActivity(item, detail), item.id, blockReasonFallback = item.reason, onCopyReason = { onAction(DetailAction.Copy(it)) })
    }
  }
  pageFooter(DetailList.Executions, state, onAction)
}

private fun DetailSections.planStatus(item: WorkItem, detail: Detail, stalledSince: Instant?) {
  this.item(key = "plan-stage-strip") {
    PlanStageStrip(item, detail)
  }
  this.item(key = "plan-status") {
    PlanStatusLine(item, detail)
  }
  this.item(key = "plan-heartbeat") {
    PlanHeartbeat(item, detail, stalledSince)
  }
}

@Composable
private fun PlanStageStrip(item: WorkItem, detail: Detail) {
  val plan = detail.standalonePlan
  val stages = plan?.stages.orEmpty().associateBy(StandalonePlanStage::phaseId)
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = ticker.now.value
  Column(Modifier.fillMaxWidth().padding(top = DocBlockGap), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth().border(1.dp, SkillBillTheme.colors.border, RoundedCornerShape(SkillBillRadius.md))) {
      PLAN_STAGES.forEach { label ->
        val stage = stages[label.id]
        val blocked = stage?.status == "blocked" || (label.id == "plan_subtasks" && plan?.blockedSubtaskId != null)
        val status = when {
          blocked -> "blocked"
          stage?.status == "running" -> "active"
          stage?.status == "completed" -> "done"
          stage?.status == "failed" -> "failed"
          stage?.status == "paused" -> "paused"
          else -> "queued"
        }
        val duration = when {
          stage?.status == "running" -> elapsedTextOrNull(stage.firstStartedAt, now)
          stage?.finishedAt != null -> stage.finishedAt?.let { elapsedTextOrNull(stage.firstStartedAt, it) }
          else -> null
        }
        Column(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          val statusColor = when {
            blocked || status == "failed" -> SkillBillTheme.colors.stBlocked
            status == "active" -> SkillBillTheme.colors.stActive
            status == "done" -> SkillBillTheme.colors.fg2
            else -> SkillBillTheme.colors.fgFaint
          }
          BasicText(label.title, style = SkillBillTheme.type.tSmMedium.copy(color = statusColor))
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label.id, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
            val badgeColor = when {
              blocked || status == "failed" -> SkillBillTheme.colors.stBlocked
              status == "done" -> SkillBillTheme.colors.stDone
              status == "active" -> SkillBillTheme.colors.stActive
              else -> SkillBillTheme.colors.fgFaint
            }
            SbLabel(status, SbLabelStyle.Tint(badgeColor))
            duration?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted)) }
          }
        }
      }
    }
    if (planFinished(item, detail)) {
      val started = stages.values.mapNotNull { it.firstStartedAt }.minOrNull()
      val finished = stages.values.mapNotNull { it.finishedAt }.maxOrNull() ?: item.finishedAt
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("Plan ready", style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
        started?.let { BasicText("total ${elapsedText(it, finished ?: now)}", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
        finished?.let { BasicText("finished ${formatInstant(it, "d MMM HH:mm")}", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)) }
      }
    }
  }
}

@Composable
private fun PlanStatusLine(item: WorkItem, detail: Detail) {
  val plan = detail.standalonePlan
  val status = when {
    plan?.blockedSubtaskId != null -> "Planning stopped at subtask ${plan.blockedSubtaskId}."
    planFinished(item, detail) -> "${plan?.totalSubtaskCount ?: 0} subtasks, all specs saved."
    plan?.currentPlanningSubtaskId != null -> "Writing subtask spec ${plan.currentPlanningSubtaskId} of ${plan.totalSubtaskCount}. ${plan.plannedSubtaskCount} specs saved."
    else -> lifecycleLabel(item.lifecycle)
  }
  BasicText(status, Modifier.fillMaxWidth().padding(top = 4.dp), style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
}

@Composable
private fun PlanHeartbeat(item: WorkItem, detail: Detail, stalledSince: Instant?) {
  val ticker = LocalUiTicker.current
  val outputAt = detail.planOutputUpdatedAt
  val elapsedOnly = item.running && item.activeDurationAsOf == null && item.startedAt != null
  if (elapsedOnly || stalledSince != null || outputAt != null) {
    DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  }
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    SbLabel(lifecycleLabel(item.lifecycle), SbLabelStyle.Tint(lifecycleColor(item.lifecycle)))
    BasicText("Elapsed", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    if (elapsedOnly) {
      BasicText(elapsedTextOrNull(item.startedAt, ticker.now.value).orEmpty(), style = SkillBillTheme.type.tSm.copy(color = colors.fg))
    } else {
      SbClock(item.activeDurationMs, item.activeDurationAsOf, live = item.running, showSeconds = true)
    }
    if (stalledSince != null) {
      BasicText("No output for ${elapsedText(stalledSince, ticker.now.value)}", style = SkillBillTheme.type.tSmMedium.copy(color = colors.stBlocked))
    } else if (outputAt != null) {
      val age = elapsedText(outputAt, ticker.now.value)
      BasicText("Last output $age ago", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    }
  }
}

private fun DetailSections.planRows(
  units: List<StandalonePlanUnit>,
  total: Int,
  wave: Int?,
  capacity: Int?,
  historyComplete: Boolean,
  detail: Detail,
  onAction: (DetailAction) -> Unit,
) {
  val saved = units.count { it.state == "saved" }
  val waveLabel = when {
    wave != null && capacity != null -> "wave $wave · $capacity capacity"
    !historyComplete -> {
      val capacityLabel = capacity?.let { "$it capacity" } ?: "capacity unknown"
      "wave number unavailable · $capacityLabel"
    }
    else -> "wave pending · capacity unknown"
  }
  section("plan-subtasks", "Subtasks", count = "$saved/$total saved · $waveLabel") {
    items(units, key = { "plan-unit-${it.subtaskId}" }) { unit ->
      val writing = unit.state == "writing"
      val startedAt = unit.startedAt
      val latestOutputAt = unit.latestOutputAt?.takeIf { startedAt == null || it >= startedAt }
      val latestLine =
        if (writing && unit.latestOutputAt != null && latestOutputAt == null) {
          null
        } else {
          unit.latestOutput ?: detail.planSubtaskLatestLines[unit.subtaskId]
        }
      val dependencyText = if (writing) latestLine else dependencyLabel(unit.dependsOn)
      Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SbLabel(unit.state, SbLabelStyle.Tint(when (unit.state) { "saved" -> SkillBillTheme.colors.stDone; "writing" -> SkillBillTheme.colors.stActive; "blocked" -> SkillBillTheme.colors.stBlocked; else -> SkillBillTheme.colors.fgFaint }))
          TreeRow(
            title = "${unit.subtaskId}. ${unit.name}",
            onClick = { if (unit.state == "saved") onAction(DetailAction.OpenSpecFile(unit.specPath)) },
            modifier = Modifier.weight(1f),
            file = unit.state == "saved",
            lead = { SbIcon(if (unit.state == "saved") LucideIcons.fileText else LucideIcons.clock, SbIconSize.Sm) },
          )
          unit.finishedAt?.let { BasicText(formatInstant(it, "HH:mm"), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint)) }
        }
        dependencyText?.let { BasicText(it, Modifier.padding(start = 36.dp, top = 3.dp), style = SkillBillTheme.type.tXs.copy(color = if (unit.state == "blocked") SkillBillTheme.colors.stBlocked else SkillBillTheme.colors.fgMuted)) }
        if (unit.state == "blocked") unit.blockedReason?.takeIf(String::isNotBlank)?.let { BasicText(it, Modifier.padding(start = 36.dp, top = 3.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.stBlocked)) }
        val ticker = LocalUiTicker.current
        val quietSince = if (writing) latestOutputAt ?: unit.startedAt.takeIf { latestLine == null } else null
        if (quietSince != null) {
          DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
        }
        if (quietSince != null && tickerAgeAtLeast(quietSince, ticker.now.value, 20 * 60)) {
          BasicText(
            "quiet ${elapsedText(quietSince, ticker.now.value)}",
            Modifier.padding(start = 36.dp, top = 3.dp),
            style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint),
          )
        }
      }
    }
  }
}

private fun DetailSections.planBundle(detail: Detail, units: List<StandalonePlanUnit>, onAction: (DetailAction) -> Unit) {
  val report = detail.standalonePlan?.report ?: return
  val savedPaths = units.filter { it.state == "saved" }.map { it.specPath }.toSet()
  val files = report.bundleFiles
  val first = listOf("decomposition-manifest.yaml", "decomposition-manifest.yml", "spec.md")
    .mapNotNull { name -> files.firstOrNull { fileName(it) == name } }
  val ordered = (first + savedPaths).distinct()
  if (ordered.isEmpty()) return
  section("plan-bundle", "Spec bundle") {
    item(key = "plan-bundle-chips") {
      PlanBundleChips(ordered) { onAction(DetailAction.OpenSpecFile(it)) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBundleChips(paths: List<String>, onOpen: (String) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    paths.forEach { path -> EvidenceChip(path) { onOpen(path) } }
  }
}

private fun dependencyLabel(ids: List<Int>): String = if (ids.isEmpty()) "no dependencies" else "after ${ids.joinToString(", ")}"

@OptIn(kotlin.time.ExperimentalTime::class)
private fun elapsedTextOrNull(start: Instant?, end: Instant): String? = start?.let { elapsedText(it, end) }

@OptIn(kotlin.time.ExperimentalTime::class)
private fun elapsedText(start: Instant, end: Instant): String {
  val seconds = ((end - start).inWholeSeconds).coerceAtLeast(0)
  return when {
    seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds}s"
  }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun tickerAgeAtLeast(start: Instant, end: Instant, seconds: Long): Boolean =
  (end - start).inWholeSeconds >= seconds
