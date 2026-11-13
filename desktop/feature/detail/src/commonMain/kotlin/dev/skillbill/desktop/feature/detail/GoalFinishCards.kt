package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalUriHandler
import dev.skillbill.desktop.core.designsystem.detail.PeekFacts
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun GoalFinishCard(item: WorkItem, detail: Detail, card: PhaseCard, state: DetailUiState, order: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  when (card.phaseId) {
    "goal_assessment" -> GoalAssessmentCard(card, detail.goalAssessment, order, onAction)
    "qa" -> GoalQaCard(item, detail, card, state, onAction)
    "qa_fix", "validate" -> SubtaskPhaseCard(
      item = item, detail = detail, subtask = null, card = card, results = null, resultState = null,
      changes = state.changes, activity = state.activity, renderedAt = state.renderedAt, onAction = onAction,
    )
    "pr" -> PullRequestCard(card, goalPullRequestUrl(detail), item.branch)
    "monitor" -> MonitorCard(card, goalPullRequestUrl(detail), state.renderedAt)
    "monitor_fix" -> MonitorFixCard(card)
  }
}

internal fun goalPullRequestUrl(detail: Detail): String? =
  detail.goalFinishCards.firstOrNull { it.phaseId == "pr" }?.pullRequestUrl
    ?: detail.goalFinishCards.firstOrNull { it.phaseId == "monitor" }?.pullRequestUrl

@Composable
private fun PullRequestCard(card: PhaseCard, url: String?, branch: String?) {
  val uri = LocalUriHandler.current
  RecordedPhasePeek(card,
    title = "Pull request", phaseId = card.phaseId, state = card.state.toPeekState(), why = card.blockedReason,
    footer = url?.let { PeekFooter("Open pull request", true) { uri.openUri(it) } },
  ) {
    branch?.let { PeekPart("Branch") { PeekOutput(LucideIcons.branch, it, tooltip = it) } }
    url?.let {
      PeekPart("Produced") {
        PeekOutput(LucideIcons.external, listOfNotNull(card.pullRequestNumber?.let { number -> "#$number" }, it).joinToString(" · "), onClick = { uri.openUri(it) }, external = true)
      }
    }
    RecordedPhaseDetails(card.output)
  }
}

@Composable
private fun MonitorFixCard(card: PhaseCard) {
  RecordedPhasePeek(card, title = "Monitor fix", phaseId = card.phaseId, state = card.state.toPeekState(), why = card.blockedReason) {
    PeekPart("Repaired") {
      val base = card.conflictBaseBranch
      if (base != null) PeekOutput(LucideIcons.warn, "Merge conflict with $base")
      else card.repairedChecks.forEach { LinkedCiCheck(it) }
    }
    card.fixCommitSha?.let { PeekPart("Result") { PeekFacts(listOf("Fix commit" to it)) } }
    RecordedPhaseDetails(card.output)
  }
}
