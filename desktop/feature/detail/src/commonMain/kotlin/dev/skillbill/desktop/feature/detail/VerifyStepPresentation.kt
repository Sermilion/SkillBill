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

internal data class VerifyStepSummaryPart(val text: String, val tone: VerifyScoreTone? = null, val mono: Boolean = false, val accent: Boolean = false)

internal fun verifyStepSummaryParts(run: VerifyRunDetails, step: VerifyRunStep): List<VerifyStepSummaryPart> {
  fun text(value: String, tone: VerifyScoreTone? = null, mono: Boolean = false) = VerifyStepSummaryPart(value, tone, mono)
  val state = verifyRunState(run)
  if (state in setOf(VerifyRunState.Stopped, VerifyRunState.Superseded) && step == auditedSteps(run).getOrNull(stoppedStepIndex(run))) return listOf(text("Stopped here", VerifyScoreTone.Amber), text("no receipt recorded", VerifyScoreTone.Faint))
  if (step.status == "pending" && step.attemptCount == 0 && !(state == VerifyRunState.Parked && step.stepId == "extract_criteria")) return listOf(text(if (step.stepId == "qa" && run.qaSelection != "on") "Off for this run" else "Not started"))
  return when (step.stepId) {
    "collect_inputs" -> listOf(text(when { run.intake?.startsWith("http") == true -> "Linear issue"; run.intake?.startsWith("spec:") == true -> "Spec file"; else -> "Free-text intake" }), text(run.target ?: "No target", mono = true), text("review ${run.reviewMode ?: "inline"}"), text("QA ${run.qaSelection ?: "off"}"))
    "extract_criteria" -> if (run.criteria.isEmpty()) listOf(text("No numbered criteria found", VerifyScoreTone.Amber)) else buildList {
      add(text("${run.criteria.size} criteria")); add(text("${run.nonGoals.size} non-goals")); add(text("${run.technicalConstraints.size} ${if (run.technicalConstraints.size == 1) "constraint" else "constraints"}"))
      if (state == VerifyRunState.Parked) add(VerifyStepSummaryPart("waiting for your confirmation", accent = true))
    }
    "gather_diff" -> listOf(text("${run.changedFiles.size} files"), text("${run.baseRevision?.take(7) ?: "?"}..${run.headRevision?.take(7) ?: "?"}", mono = true))
    "feature_flag_audit" -> run.flagAudit?.let { audit ->
      if (audit.verdict == "skipped") listOfNotNull(text("Skipped"), audit.skipReason?.let { text(it, VerifyScoreTone.Faint) })
      else listOfNotNull(text(if (audit.checks.any { !it.passed }) "Failed" else "Passed", if (audit.checks.any { !it.passed }) VerifyScoreTone.Red else VerifyScoreTone.Green), audit.flagName?.let { text(it, mono = true) }, text("${audit.checks.count { it.passed }}/${audit.checks.size} checks"))
    } ?: listOf(text("No receipt recorded"))
    "code_review" -> if (step.status == "running") listOf(text("Reviewing", VerifyScoreTone.Active), text("specialists run in parallel")) else run.reviewFindings?.let { review ->
      val verdict = review.verdict.orEmpty().replace('_', ' ')
      listOf(text(verdict.replaceFirstChar { it.uppercaseChar() }, if (verdict.contains("changes")) VerifyScoreTone.Red else VerifyScoreTone.Amber)) + review.items.groupingBy { it.severity.lowercase() }.eachCount().map { (severity, count) -> text("$count $severity", when (severity) { "major", "blocker" -> VerifyScoreTone.Red; "minor" -> VerifyScoreTone.Amber; else -> VerifyScoreTone.Faint }) }
    } ?: listOf(text("No receipt recorded"))
    "unit_test_value_check" -> run.unitTestValue?.let { audit ->
      if (audit.verdict == "skipped") listOfNotNull(text("Skipped"), audit.skipReason?.let { text(it, VerifyScoreTone.Faint) })
      else listOfNotNull(audit.overall?.let { text(it) }) + audit.rows.groupingBy { it.verdict.lowercase() }.eachCount().map { (verdict, count) -> text("$count $verdict", when (verdict) { "valuable" -> VerifyScoreTone.Green; "weak" -> VerifyScoreTone.Amber; "harmful" -> VerifyScoreTone.Red; else -> VerifyScoreTone.Faint }) }
    } ?: listOf(text("No receipt recorded"))
    "completeness_audit" -> listOf("PASS" to "met", "PARTIAL" to "partial", "FAIL" to "missing").mapNotNull { (status, label) ->
      val count = run.completenessAudit?.grades?.count { it.status == status } ?: 0
      if (count == 0 && status != "PASS") null else text("$count $label", when (status) { "PASS" -> VerifyScoreTone.Green; "PARTIAL" -> VerifyScoreTone.Amber; else -> VerifyScoreTone.Red })
    }
    "qa" -> if (run.qaSelection != "on") listOf(text("Off for this run")) else run.qa?.let { qa ->
      val gaps = qa.assertions.count { it.outcome == dev.skillbill.desktop.core.domain.model.QaAssertionOutcome.NotExercised }
      buildList {
        add(if (qa.findings.isEmpty()) text("Passed", VerifyScoreTone.Green) else text("${qa.findings.size} open ${if (qa.findings.size == 1) "finding" else "findings"}", VerifyScoreTone.Red))
        add(text("${qa.assertions.count { it.outcome == dev.skillbill.desktop.core.domain.model.QaAssertionOutcome.Passed }} of ${qa.assertions.size} checks passed"))
        if (gaps > 0) add(text("$gaps not tested", VerifyScoreTone.Amber))
      }
    } ?: listOf(text("No report recorded"))
    "verdict" -> listOf(text(run.verdict?.verdict ?: "No verdict recorded", verifyStepTone(run, step)))
    else -> emptyList()
  }
}
