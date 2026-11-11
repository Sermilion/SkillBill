package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun verifySummary(run: VerifyRunDetails): String? = run.verdict?.summary ?: when (verifyRunState(run)) {
  VerifyRunState.Running, VerifyRunState.Parked, VerifyRunState.Stopped -> run.criteria.firstOrNull()?.text
  VerifyRunState.Pending, VerifyRunState.Superseded, VerifyRunState.Finished -> null
}

internal fun verifyReportText(run: VerifyRunDetails): String = buildList {
  run.verdict?.verdict?.let(::add)
  if (isNotEmpty() && run.verdict?.summary != null) add("")
  run.verdict?.summary?.let(::add)
  val findings = run.verdict?.findings.orEmpty()
  if (isNotEmpty() && findings.isNotEmpty()) add("")
  addAll(findings)
}.joinToString("\n")

internal fun DetailSections.verifyDocument(state: DetailUiState, run: VerifyRunDetails, onAction: (DetailAction) -> Unit, onScrollToSection: (String) -> Unit) {
  val intake = run.intake
  if (intake != null && isFreeTextIntake(intake) && verifyRunState(run) != VerifyRunState.Parked) {
    val key = "verify-intake:${run.workflowId}"
    val sectionCollapse = collapse
    item(key = key) {
      Column(Modifier.padding(top = 8.dp)) {
        VerifyFold("Intake", !sectionCollapse.isCollapsed(key, default = true), { sectionCollapse.toggle(key, default = true) }) { VerifyQuote(intake) }
      }
    }
  }

  verifySummary(run)?.let { summary ->
    item(key = "verify-summary") { VerifyWorkSummary(run, summary) }
  }

  item(key = "verify-outcome") {
    VerifyOutcomeCard(verifyOutcomeView(run), onScrollToSection)
  }
  val items = verifyFixItems(run, state.criterionMarks)
  verifyCriteriaSection(verifyCriteriaView(run, state.verifyCriteriaFilter), run, state, items, onAction)
  verifyStepsTimeline(run, state, items, onAction, onScrollToSection)
  (state.sections as? SectionsUi.Loaded)?.detail?.let { detail ->
    val sectionCollapse = collapse
    item(key = "verify-activity") {
      Column(Modifier.padding(top = 24.dp)) {
        VerifyFold("Activity", !sectionCollapse.isCollapsed("verify-activity", default = true), { sectionCollapse.toggle("verify-activity", default = true) }, note = "agent output and tool calls", strong = true) {
          Column(Modifier.padding(start = 20.dp, top = 6.dp)) {
            ActivityTimeline(standaloneActivity(state.header, detail), state.header.id, blockReasonFallback = state.header.reason, onCopyReason = { onAction(DetailAction.Copy(it)) })
          }
        }
      }
    }
  }

}
