package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PhaseTabs
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

internal val GOAL_FINISH_PHASES = listOf("goal_assessment", "qa", "qa_fix", "validate", "pr", "monitor", "monitor_fix")

internal fun goalFinishTabs(detail: Detail) = subjectPhaseTabs(detail, SubtaskKey.GoalFinish)

@Composable
internal fun GoalFinishPhases(item: WorkItem, detail: Detail, state: DetailUiState, order: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  val tabs = goalFinishTabs(detail)
  val key = SubtaskKey.GoalFinish
  val selected = selectedPhase(tabs, detail.goalFinishCards, state.phaseChoice[PhaseChoiceKey(item.id, key)])
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PhaseTabs(
      tabs, selected,
      onActivate = { onAction(DetailAction.SelectPhase(item.id, key, it)) },
      onNavigate = { onAction(DetailAction.SelectPhase(item.id, key, it, PhaseSelectionIntent.Navigate)) },
      label = "Goal finish phases",
    )
    detail.degradedGoalStatusSource?.let { source ->
      UnreadablePhaseResults(source, lead = "Goal finish results could not be read.") { onAction(DetailAction.Retry) }
    }
    val card = detail.goalFinishCards.firstOrNull { it.phaseId == selected }
    when {
      detail.goalFinishCards.isNotEmpty() && selected == null -> BasicText("Select a phase to see what it did.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      card != null -> GoalFinishCard(item, detail, card, state, order, onAction)
      detail.goalAssessment != null || detail.goalQa != null -> BasicText("Phase results are available, but no phase card was recorded.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      detail.degradedGoalStatusSource != null -> Unit
      else -> BasicText("No phase has run yet.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}
