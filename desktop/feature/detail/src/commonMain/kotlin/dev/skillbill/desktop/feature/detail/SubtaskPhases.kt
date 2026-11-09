package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PhaseTab
import dev.skillbill.desktop.core.designsystem.detail.PhaseTabs
import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind
import kotlin.time.Instant

internal fun phaseTabs(order: List<String>, cards: List<PhaseCard>): List<PhaseTab> = order.map { id ->
  val card = cards.firstOrNull { it.phaseId == id }
  PhaseTab(id, phaseTitle(id), card?.state?.toPipelineState() ?: PipelineStepState.Pending, card != null)
}

internal fun selectedPhase(tabs: List<PhaseTab>, chosen: String?): String? {
  val open = tabs.filter { it.openable }
  if (open.isEmpty()) return null
  return chosen?.takeIf { id -> open.any { it.id == id } }
    ?: open.firstOrNull { it.state == PipelineStepState.Current || it.state == PipelineStepState.Failed || it.state == PipelineStepState.Waiting }?.id
    ?: open.lastOrNull { it.state == PipelineStepState.Done }?.id
    ?: open.last().id
}

internal fun phasesNote(tabs: List<PhaseTab>, running: Boolean): String {
  val cards = tabs.count { it.openable }
  return if (running) "$cards of ${tabs.size}" else cards.toString()
}

internal fun PhaseCardState.toPipelineState(): PipelineStepState = when (this) {
  PhaseCardState.Running -> PipelineStepState.Current
  PhaseCardState.Done -> PipelineStepState.Done
  PhaseCardState.Failed -> PipelineStepState.Failed
  PhaseCardState.Blocked, PhaseCardState.Paused -> PipelineStepState.Waiting
  PhaseCardState.Skipped -> PipelineStepState.Skipped
}

internal fun PhaseCardState.toPeekState(): dev.skillbill.desktop.core.designsystem.detail.PeekState = when (this) {
  PhaseCardState.Running -> dev.skillbill.desktop.core.designsystem.detail.PeekState.Now
  PhaseCardState.Done -> dev.skillbill.desktop.core.designsystem.detail.PeekState.Done
  PhaseCardState.Failed -> dev.skillbill.desktop.core.designsystem.detail.PeekState.Fail
  PhaseCardState.Blocked, PhaseCardState.Paused -> dev.skillbill.desktop.core.designsystem.detail.PeekState.Wait
  PhaseCardState.Skipped -> dev.skillbill.desktop.core.designsystem.detail.PeekState.Skipped
}

internal fun phaseTitle(id: String): String = when (id) {
  "goal_assessment" -> "Goal assessment"
  "qa" -> "QA"
  "qa_fix" -> "QA fix"
  "monitor_fix" -> "Monitor fix"
  "preplan" -> "Preplan"
  "plan" -> "Plan"
  "implement" -> "Implement"
  "audit" -> "Audit"
  "review" -> "Review"
  "validate" -> "Validate"
  "write_history" -> "Write history"
  "commit_push" -> "Commit and push"
  "pr" -> "Pull request"
  "monitor" -> "Monitor"
  else -> id
}

@Composable
internal fun SubtaskPhasesSection(
  item: WorkItem,
  detail: Detail,
  subtask: WorkSubtask,
  choice: Map<PhaseChoiceKey, String>,
  activity: ActivityResult,
  renderedAt: Instant,
  onAction: (DetailAction) -> Unit,
) {
  val results = detail.phaseResults.firstOrNull { it.subtaskId == subtask.id }
  val tabs = phaseTabs(detail.stepPipeline, results?.phaseCards.orEmpty())
  val key = SubtaskKey.Subtask(subtask.id)
  val selected = selectedPhase(tabs, choice[PhaseChoiceKey(item.id, key)])
  val running = subtaskStatusKind(subtask.status) in setOf(StatusKind.Active, StatusKind.Stalled, StatusKind.Planning)
  PaneSection(
    title = "Phases",
    end = { BasicText(phasesNote(tabs, running), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted)) },
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PhaseTabs(tabs, selected, { onAction(DetailAction.SelectPhase(item.id, key, it)) }, label = "Phases of subtask ${subtaskNumber(subtask.id)}")
      val card = selected?.let { id -> results?.phaseCards?.firstOrNull { it.phaseId == id } }
      if (card == null) BasicText("No phase has run yet.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      else SubtaskPhaseCard(item, detail, subtask, card, results, activity, renderedAt, onAction)
    }
  }
}
