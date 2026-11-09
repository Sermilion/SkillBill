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

internal fun goalFinishTabs(detail: Detail) = phaseTabs(GOAL_FINISH_PHASES, detail.goalFinishCards)

@Composable
internal fun GoalFinishPhases(item: WorkItem, detail: Detail, state: DetailUiState, order: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  val tabs = goalFinishTabs(detail)
  val key = SubtaskKey.GoalFinish
  val selected = selectedPhase(tabs, state.phaseChoice[PhaseChoiceKey(item.id, key)])
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PhaseTabs(tabs, selected, { onAction(DetailAction.SelectPhase(item.id, key, it)) }, label = "Goal finish phases")
    val card = detail.goalFinishCards.firstOrNull { it.phaseId == selected }
    if (card == null) BasicText("No phase has run yet.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    else GoalFinishCard(item, detail, card, state, order, onAction)
  }
}
