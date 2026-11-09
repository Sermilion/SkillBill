package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PhasePeek
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun GoalQaCard(
  item: WorkItem,
  detail: Detail,
  card: PhaseCard,
  state: DetailUiState,
  onAction: (DetailAction) -> Unit,
) {
  val collapse = rememberSectionCollapse()
  PhasePeek(title = "QA", phaseId = card.phaseId, state = card.state.toPeekState(), why = card.blockedReason) {
    if (card.state == PhaseCardState.Running && state.qaPreviewVisible) {
      selectedQaPreview(item, detail, state.selectedQaRun, SubtaskKey.GoalFinish)?.let { run ->
        PeekPart("Live", live = true) { QaPreview(run.invocationId, state.qaPreview) }
      }
    }
    GoalQaHistory(detail.goalQa?.details, collapse, onAction)
    PeekPart("Results") {
      val qa = detail.goalQa
      if (qa == null) BasicText("No QA result is available for this run.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      else {
        val fixing = detail.goalFinishCards.any { it.phaseId == "qa_fix" && it.state == PhaseCardState.Running }
        QaResultsContent(qaResultsView(goalQaInput(qa, item.running, fixing)), collapse, onAction, showVerdictMark = true)
      }
    }
  }
}
