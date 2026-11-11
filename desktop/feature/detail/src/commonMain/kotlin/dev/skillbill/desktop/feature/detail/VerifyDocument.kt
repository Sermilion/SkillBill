package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
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
    section("verify-intake:${run.workflowId}", "Intake", spaceBefore = DocBlockGap, collapsedByDefault = true) {
      item(key = "verify-intake-text:${run.workflowId}") { VerifyQuote(intake) }
    }
  }
  verifySummary(run)?.let { summary ->
    item(key = "verify-summary") { VerifyWorkSummary(run, summary) }
  }

  item(key = "verify-outcome") {
    VerifyOutcomeCard(verifyOutcomeView(run), onScrollToSection)
  }
  val items = verifyFixItems(run, state.criterionMarks)
  val allowed = verifySelectionAllowed(run)
  verifyCriteriaSection(verifyCriteriaView(run, state.verifyCriteriaFilter), run, state, items, onAction)
  verifyStepsTimeline(run, state, items, onAction, onScrollToSection)
  (state.sections as? SectionsUi.Loaded)?.detail?.let { detail ->
    section("verify-activity", "Activity", collapsedByDefault = true) {
      item(key = "verify-activity-body") {
        ActivityTimeline(standaloneActivity(state.header, detail), state.header.id, blockReasonFallback = state.header.reason, onCopyReason = { onAction(DetailAction.Copy(it)) })
      }
    }
  }
  item(key = "verify-fix-bar") { VerifyFixBar(items, state, allowed, onAction) }
}
