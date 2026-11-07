package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun verifySummary(run: VerifyRunDetails): String? = run.verdict?.summary ?: when (verifyRunState(run)) {
  VerifyRunState.Pending, VerifyRunState.Running, VerifyRunState.Parked, VerifyRunState.Stopped -> run.criteria.firstOrNull()?.text
  VerifyRunState.Superseded, VerifyRunState.Finished -> null
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
      item(key = "verify-intake-text:${run.workflowId}") { BasicText(intake) }
    }
  }
  verifySummary(run)?.let { summary ->
    val isIntent = run.verdict?.summary == null
    item(key = "verify-summary") {
      BasicText(summary, Modifier.padding(top = DocBlockGap), maxLines = if (isIntent) 1 else Int.MAX_VALUE)
    }
  }
  val showChanges = run.changedFiles.isNotEmpty() && verifyRunState(run) != VerifyRunState.Parked
  val showCopy = verifyRunState(run) == VerifyRunState.Finished
  val runAgain = canVerifyAgain(run, (state.sections as? SectionsUi.Loaded)?.runOptions?.agentChoices.orEmpty())
  if (showChanges || showCopy || runAgain) item(key = "verify-actions") {
    androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
      if (showChanges) SbButton("Open changes · ${run.changedFiles.size}", { onAction(DetailAction.OpenChangesWindow()) })
      if (showCopy) SbButton("Copy report", { onAction(DetailAction.Copy(verifyReportText(run))) })
      if (runAgain) SbButton(if (verifyRunState(run) == VerifyRunState.Finished) "Verify again" else "Run again", { onAction(DetailAction.VerifyAgain) }, enabled = !state.verifyAgainInFlight)
    }
  }
  item(key = "verify-outcome") {
    VerifyOutcomeCard(verifyOutcomeView(run), onScrollToSection)
  }
  val items = verifyFixItems(run, state.criterionMarks)
  val allowed = verifySelectionAllowed(run)
  verifyCriteriaSection(verifyCriteriaView(run, state.verifyCriteriaFilter), run, state, items, onAction)
  verifyStepsTimeline(run, state, items, onAction, onScrollToSection)
  item(key = "verify-fix-bar") { VerifyFixBar(items, state, allowed, onAction) }
}
