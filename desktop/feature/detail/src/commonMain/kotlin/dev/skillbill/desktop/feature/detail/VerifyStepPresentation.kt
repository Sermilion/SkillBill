package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyRunStep

internal fun verifyStepTone(run: VerifyRunDetails, step: VerifyRunStep): VerifyScoreTone {
  val state = verifyRunState(run)
  if (state == VerifyRunState.Parked && step.stepId == "extract_criteria") return VerifyScoreTone.Amber
  if (state == VerifyRunState.Superseded && step.stepId == "extract_criteria") return VerifyScoreTone.Faint
  if (step.status == "running") return if (state == VerifyRunState.Stopped) VerifyScoreTone.Red else VerifyScoreTone.Active
  if (step.status in setOf("failed", "blocked")) return VerifyScoreTone.Red
  if (step.status != "completed") return VerifyScoreTone.Faint
  return when (step.stepId) {
    "code_review" -> if (run.reviewFindings?.findings?.isNotEmpty() == true) VerifyScoreTone.Amber else VerifyScoreTone.Green
    "completeness_audit" -> when {
      run.completenessAudit?.grades?.any { it.status == "FAIL" } == true -> VerifyScoreTone.Red
      run.completenessAudit?.grades?.any { it.status == "PARTIAL" } == true -> VerifyScoreTone.Amber
      else -> VerifyScoreTone.Green
    }
    "unit_test_value_check" -> if (run.unitTestValue?.rows?.any { !it.verdict.equals("Valuable", true) } == true) VerifyScoreTone.Amber else VerifyScoreTone.Green
    "feature_flag_audit" -> if (run.flagAudit?.checks?.any { !it.passed } == true) VerifyScoreTone.Amber else VerifyScoreTone.Green
    "qa" -> if (run.qaVerdict in setOf("findings_open", "incomplete")) VerifyScoreTone.Amber else VerifyScoreTone.Green
    "verdict" -> when (run.verdict?.verdict) { "REQUEST CHANGES" -> VerifyScoreTone.Red; "APPROVE WITH FIXES" -> VerifyScoreTone.Amber; else -> VerifyScoreTone.Green }
    else -> VerifyScoreTone.Green
  }
}

internal fun verifyStepSummary(run: VerifyRunDetails, step: VerifyRunStep): String {
  if (step.status == "pending" && step.attemptCount == 0) return if (step.stepId == "qa" && run.qaSelection != "on") "Off for this run" else "Not started"
  return when (step.stepId) {
    "collect_inputs" -> "${verifyIntakeLabel(run.intake)} · ${run.target ?: "No target"} · review ${run.reviewMode ?: "inline"} · QA ${run.qaSelection ?: "off"}"
    "extract_criteria" -> if (run.criteria.isEmpty()) "No numbered criteria found" else "${run.criteria.size} criteria · ${run.nonGoals.size} non-goals · ${run.technicalConstraints.size} constraints"
    "gather_diff" -> "${run.changedFiles.size} files · ${run.baseRevision?.take(8) ?: "?"}..${run.headRevision?.take(8) ?: "?"}"
    "feature_flag_audit" -> run.flagAudit?.let { it.skipReason ?: listOfNotNull(it.verdict, it.flagName, "${it.checks.count { check -> check.passed }}/${it.checks.size} checks").joinToString(" · ") } ?: "No receipt recorded"
    "code_review" -> if (step.status == "running") "Reviewing · specialists run in parallel" else run.reviewFindings?.let { "${it.verdict.orEmpty().replace('_', ' ')} · ${it.findings.size} findings" } ?: "No receipt recorded"
    "unit_test_value_check" -> run.unitTestValue?.let { it.skipReason ?: listOfNotNull(it.overall, verifyScoreNotes(run)["Tests"]).joinToString(" · ") } ?: "No receipt recorded"
    "completeness_audit" -> verifyScoreNotes(run)["Criteria"].orEmpty()
    "qa" -> if (run.qaSelection != "on") "Off for this run" else run.qaVerdict?.let { "$it · ${verifyScoreNotes(run)["QA"]}" } ?: "No report recorded"
    "verdict" -> run.verdict?.verdict ?: "No verdict recorded"
    else -> ""
  }
}
