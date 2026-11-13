package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PeekSignal
import dev.skillbill.desktop.core.designsystem.detail.PeekState
import dev.skillbill.desktop.core.designsystem.detail.PeekTimelineRow
import dev.skillbill.desktop.core.designsystem.detail.PhasePeek
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind
import kotlin.time.Instant

@Composable
internal fun SubtaskPhaseCard(
  item: WorkItem,
  detail: Detail,
  subtask: WorkSubtask?,
  card: PhaseCard,
  results: SubtaskPhaseResults?,
  resultState: SubtaskResultsState?,
  changes: ChangesUi,
  activity: ActivityResult,
  renderedAt: Instant,
  onAction: (DetailAction) -> Unit,
) {
  val state = card.state.toPeekState()
  val model = listOfNotNull(card.model, card.effort).joinToString(" · ").ifBlank { null }
  val start = card.startedAt?.let { formatInstant(it, "d MMM HH:mm") }
  val duration = card.durationMillis?.let(::activityDuration)
  val path = subtask?.let { subtaskSpecPath(detail, it.id) }
  val phaseActivity = activityRows(activity, subtask?.id, card)
  var validationExpanded by remember(
    item.id, subtask?.id, card.phaseId, card.round, card.startedAt, card.output?.validationReceipt,
  ) {
    mutableStateOf(false)
  }
  val footer = when (card.phaseId) {
    "plan" -> path?.let { PeekFooter("Open spec file", false) { onAction(DetailAction.OpenSpecFile(it)) } }
    "implement" ->
      subtask?.id?.takeIf { implementChangesEligible(changes, it) }?.let { id ->
        PeekFooter("Open changes", false) {
          onAction(DetailAction.OpenChangesWindow(ChangeScope.Subtasks(id, id)))
        }
      }
    "commit_push" ->
      card.output?.pushedBranch?.takeIf { it.isNotBlank() }?.let { branch ->
        PeekFooter("Open branch", true) { onAction(DetailAction.OpenBranch(branch)) }
      }
    else -> null
  }
  PhasePeek(
    title = phaseTitle(card.phaseId), phaseId = card.phaseId, state = state,
    word = if (card.state == PhaseCardState.Paused) "Paused" else null,
    agentId = card.agentId, model = model, round = card.round?.let { "round $it" }, startLabel = start,
    durationLabel = duration, why = if (card.state == PhaseCardState.Blocked) card.blockedReason else null,
    activity = phaseActivity, footer = footer,
  ) {
    val activityLabel = card.activityLabel
    val edit = card.edit
    if (card.state == PhaseCardState.Running || card.state == PhaseCardState.Blocked) {
      if (!activityLabel.isNullOrBlank() || edit != null || (card.state == PhaseCardState.Blocked && !card.blockedReason.isNullOrBlank())) {
        PeekPart(if (card.state == PhaseCardState.Blocked) "Waiting on" else "Now", live = card.state == PhaseCardState.Running) {
          if (!activityLabel.isNullOrBlank()) {
            PeekSignal(activityLabel, card.state == PhaseCardState.Running, LucideIcons.zap, ago = card.activityAt?.let { elapsedTextOrNull(it, renderedAt) })
          } else if (card.state == PhaseCardState.Blocked) {
            card.blockedReason?.let { PeekSignal(it, false, LucideIcons.warn) }
          }
          edit?.let {
            PeekSignal(
              "edited ${it.samplePaths.firstOrNull()?.let(::fileName) ?: "files"}",
              false,
              LucideIcons.pencil,
              keep = AnnotatedString("+${it.insertions} −${it.deletions}"),
              ago = elapsedTextOrNull(it.recordedAt, renderedAt),
            )
          }
        }
      }
    }
    produced(
      detail, card, subtask, results, resultState, path, validationExpanded,
      { validationExpanded = !validationExpanded }, item, onAction,
    )
  }
}

@Composable
private fun produced(
  detail: Detail,
  card: PhaseCard,
  subtask: WorkSubtask?,
  results: SubtaskPhaseResults?,
  resultState: SubtaskResultsState?,
  path: String?,
  checksExpanded: Boolean,
  onToggleChecks: () -> Unit,
  item: WorkItem,
  onAction: (DetailAction) -> Unit,
) {
  when (card.phaseId) {
    "preplan" -> PreplanRecordedOutput(detail, onAction)
    "plan" -> PlanRecordedOutput(path, onAction)
    "implement", "simplify" -> EditRecordedOutput(card.edit)
    "qa_fix" -> {
      val edit = card.edit
      if (edit != null) EditRecordedOutput(edit)
      else if (card.files.isNotEmpty()) {
        PeekPart("Produced") { card.files.forEach { PeekOutput(LucideIcons.file, it, tooltip = it) } }
      }
    }
    "build" -> BuildRecordedOutput(card.output?.buildReceipt)
    "audit" -> if (subtask != null && resultState is SubtaskResultsState.Shown) {
      SubtaskAuditSection(results, subtaskStatusKind(subtask.status), subtask.id, card.round)
    }
    "review" -> if (resultState is SubtaskResultsState.Shown) {
      when (val review = resultState.review) {
        is SubtaskReviewState.Passes -> SubtaskReviewSection(review.passes, review.runningReviewPass)
        SubtaskReviewState.RunsAfterAudit -> PeekPart("Review") {
          BasicText("Runs after audit", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
        }
        SubtaskReviewState.Omitted -> Unit
      }
    }
    "validate" -> card.output?.let { output ->
      ValidateRecordedOutput(output, subtask?.id, detail, checksExpanded, onToggleChecks)
    }
    "write_history" -> card.output?.historyReceipt?.let { receipt ->
      HistoryRecordedOutput(receipt, detail, item, onAction)
    }
    "commit_push" -> card.output?.let { CommitRecordedOutput(it) }
  }
}

private fun activityRows(activity: ActivityResult, subtaskId: Int?, card: PhaseCard): List<PeekTimelineRow> {
  val listed = activity as? ActivityResult.Listed ?: return emptyList()
  val group = listed.groups.firstOrNull { it.subtaskId == subtaskId } ?: return emptyList()
  return group.phaseEvents.filter { it.phaseId == card.phaseId }.takeLast(5).map {
    PeekTimelineRow(formatInstant(it.at, "HH:mm"), it.label, if (it.blocked) PeekState.Wait else null)
  }
}
