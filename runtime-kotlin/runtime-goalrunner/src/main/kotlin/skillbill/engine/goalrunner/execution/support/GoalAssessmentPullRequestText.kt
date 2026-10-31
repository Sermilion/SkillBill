package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentRunStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification

internal fun GoalAssessmentRecordRead.pullRequestLines(): List<String> =
  when (this) {
    GoalAssessmentRecordRead.Absent -> listOf("- Assessment did not run: no assessment record was stored.")
    is GoalAssessmentRecordRead.Corrupt ->
      listOf("- Warning: the stored assessment record is unreadable (${reason.flattenedForMarkdown()}).")
    is GoalAssessmentRecordRead.Recorded -> record.pullRequestLines()
  }

private fun GoalAssessmentRecord.pullRequestLines(): List<String> {
  if (exitReason == GoalAssessmentExitReason.SKIPPED_NO_CHANGES) {
    return listOf("- Assessment did not run: the goal made no changes to assess.")
  }
  val findings = rounds.flatMap { it.findings }
  val lastRound = rounds.lastOrNull()
  val awaitingReverification =
    if (lastRound?.fix?.status == GoalAssessmentFixStatus.COMPLETED) {
      findings.filter { it.disposition == GoalAssessmentDisposition.FIX }.map { it.findingRef }.toSet()
    } else {
      emptySet()
    }
  val open = openFindingRefs.toSet()
  val reported =
    findings.mapNotNull { finding ->
      finding.categories(open, awaitingReverification).takeIf { it.isNotEmpty() }?.let { finding.pullRequestLine(it) }
    }
  val unresolved = openFindingRefs.filter { ref -> findings.none { it.findingRef == ref } }
  val notRestored =
    lastRound?.regressions.orEmpty().filter { it.state == GoalAssessmentRegressionState.NOT_RESTORED }
  val failedRuns =
    rounds.flatMap { round ->
      round.assessorRuns.filter { it.status != GoalAssessmentRunStatus.COMPLETED }.map { run ->
        val subtask = run.subtaskId?.let { " for subtask $it" }.orEmpty()
        "- Warning: the ${run.role.wireValue} run$subtask ${run.status.wireValue} in round ${round.round}."
      }
    }
  return buildList {
    add("- Exit reason: ${exitReason?.wireValue ?: "none recorded"} after ${rounds.size} round(s).")
    addAll(reported)
    unresolved.forEach { add("- Warning: open finding $it is missing from the retained findings.") }
    notRestored.forEach {
      add("- ${it.regressionRef} not restored: ${it.evidence.flattenedForMarkdown()}")
    }
    addAll(failedRuns)
  }
}

private fun GoalAssessmentFinding.categories(
  open: Set<String>,
  awaitingReverification: Set<String>,
): List<String> =
  buildList {
    val blocking = kind == GoalAssessmentFindingKind.DEFECT && severity.blocksAdvance
    val reportOnly = kind == GoalAssessmentFindingKind.TEST_VALUE || !severity.blocksAdvance
    val stillOpen = state == GoalAssessmentFindingState.OPEN
    if (findingRef in open) add(if (findingRef in awaitingReverification) "fixed, not re-verified" else "open")
    if (blocking && verification == GoalAssessmentVerification.UNVERIFIED) add("unverified")
    if (verification == GoalAssessmentVerification.REFUTED) add("refuted")
    if (reportOnly && stillOpen && disposition == GoalAssessmentDisposition.PR_ONLY) add("pr_only")
  }

private fun GoalAssessmentFinding.pullRequestLine(categories: List<String>): String {
  val evidence = verificationEvidence.flattenedForMarkdown().takeIf(String::isNotEmpty)?.let { " Evidence: $it" }
  val guarded = guardedRef?.let { " Guards: ${it.flattenedForMarkdown()}" }
  val location = "$path:$line".flattenedForMarkdown()
  return "- $findingRef (${severity.wireValue}) $location " +
    "[${categories.joinToString(", ")}; verification: ${verification.wireValue}]: " +
    failureScenario.flattenedForMarkdown() + evidence.orEmpty() + guarded.orEmpty()
}
