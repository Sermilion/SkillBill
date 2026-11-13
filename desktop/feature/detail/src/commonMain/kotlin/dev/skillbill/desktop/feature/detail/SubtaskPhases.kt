package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PhaseTab
import dev.skillbill.desktop.core.designsystem.detail.PhaseTabs
import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind
import kotlin.time.Instant

internal fun phaseTabs(order: List<String>, cards: List<PhaseCard>): List<PhaseTab> = (order + cards.map { it.phaseId }).distinct().map { id ->
  val card = cards.firstOrNull { it.phaseId == id }
  PhaseTab(id, phaseTitle(id), card?.state?.toPipelineState() ?: PipelineStepState.Pending, card != null)
}

/** The recorded cards of one subject; the view model and both renderers resolve disclosure against these. */
internal fun subjectPhaseCards(detail: Detail, key: SubtaskKey): List<PhaseCard> = when (key) {
  SubtaskKey.GoalFinish -> detail.goalFinishCards
  is SubtaskKey.Subtask -> detail.phaseResults.firstOrNull { it.subtaskId == key.id }?.phaseCards.orEmpty()
}

internal fun subjectPhaseTabs(detail: Detail, key: SubtaskKey): List<PhaseTab> =
  phaseTabs(if (key == SubtaskKey.GoalFinish) GOAL_FINISH_PHASES else detail.stepPipeline, subjectPhaseCards(detail, key))

internal fun selectedPhase(tabs: List<PhaseTab>, cards: List<PhaseCard>, choice: PhaseDisclosure?): String? {
  val open = tabs.filter { it.openable }
  if (open.isEmpty()) return null
  return when (choice) {
    PhaseDisclosure.Folded -> null
    is PhaseDisclosure.Open -> choice.phaseId.takeIf { id -> open.any { it.id == id } }
    PhaseDisclosure.Auto, null -> {
      val cardsById = cards.associateBy { it.phaseId }
      val orderedCards = open.mapNotNull { cardsById[it.id] }
      listOf(PhaseCardState.Running, PhaseCardState.Blocked, PhaseCardState.Failed)
        .firstNotNullOfOrNull { wanted -> orderedCards.firstOrNull { it.state == wanted }?.phaseId }
    }
  }
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
  choice: Map<PhaseChoiceKey, PhaseDisclosure>,
  activity: ActivityResult,
  renderedAt: Instant,
  phaseResultsPagePending: Boolean,
  changes: ChangesUi,
  onAction: (DetailAction) -> Unit,
) {
  val results = detail.phaseResults.firstOrNull { it.subtaskId == subtask.id }
  val cards = results?.phaseCards.orEmpty()
  val tabs = phaseTabs(detail.stepPipeline, cards)
  val key = SubtaskKey.Subtask(subtask.id)
  val selected = selectedPhase(tabs, cards, choice[PhaseChoiceKey(item.id, key)])
  val running = subtaskStatusKind(subtask.status) in setOf(StatusKind.Active, StatusKind.Stalled, StatusKind.Planning)
  val readState = subtaskResultsState(subtaskStatusKind(subtask.status), results, detail.degradedGoalStatusSource, phaseResultsPagePending)
  PaneSection(
    title = "Phases",
    end = { BasicText(phasesNote(tabs, running), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted)) },
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      PhaseTabs(
        tabs, selected,
        onActivate = { onAction(DetailAction.SelectPhase(item.id, key, it)) },
        onNavigate = { onAction(DetailAction.SelectPhase(item.id, key, it, PhaseSelectionIntent.Navigate)) },
        label = "Phases of subtask ${subtaskNumber(subtask.id)}",
      )
      when (readState) {
        SubtaskResultsState.Upcoming -> BasicText("Runs after implementation", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
        SubtaskResultsState.Loading -> PhaseResultsNotice("Loading phase results.") { onAction(DetailAction.Retry) }
        is SubtaskResultsState.Unreadable -> UnreadablePhaseResults(readState.source) { onAction(DetailAction.Retry) }
        is SubtaskResultsState.Shown -> {
          val muted = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted)
          val auditKnown = results?.auditResult != null || results?.audit != null
          val reviewKnown = results?.reviewPasses?.isNotEmpty() == true || results?.runningReviewPass != null
          val missingAudit = auditKnown && cards.none { it.phaseId == "audit" }
          val missingReview = reviewKnown && cards.none { it.phaseId == "review" }
          if (cards.isEmpty() && !missingAudit && !missingReview) BasicText("No phase has run yet.", style = muted)
          if (missingAudit) BasicText("Audit results are available, but no audit phase card was recorded.", style = muted)
          if (missingReview) BasicText("Review results are available, but no review phase card was recorded.", style = muted)
        }
      }
      if (cards.isNotEmpty() && selected == null) {
        BasicText("Select a phase to see what it did.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      }
      val card = selected?.let { id -> results?.phaseCards?.firstOrNull { it.phaseId == id } }
      if (card != null) {
        SubtaskPhaseCard(
          item, detail, subtask, card, results, readState, changes, activity, renderedAt, onAction,
        )
      }
    }
  }
}

@Composable
private fun PhaseResultsNotice(message: String, onRefresh: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(message, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    SbButton("Refresh", onRefresh, size = SbControlSize.Small)
  }
}

@Composable
internal fun UnreadablePhaseResults(
  source: String,
  lead: String = "Review and audit ran, but their results could not be read.",
  modifier: Modifier = Modifier,
  onRefresh: () -> Unit,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Callout(FeedbackTone.Warn, body = buildAnnotatedString {
      append("$lead The status read was degraded at ")
      withStyle(SpanStyle(fontFamily = SkillBillTheme.type.monoSm.fontFamily)) { append(source) }
      append(".")
    }, modifier = Modifier.fillMaxWidth())
    SbButton("Refresh", onRefresh, size = SbControlSize.Small)
  }
}
