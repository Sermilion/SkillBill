package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import kotlin.time.Instant

internal data class VerifyScoreSegment(val count: Int, val tone: VerifyScoreTone)
internal enum class VerifyScoreTone { Green, Amber, Red, Active, Faint }
internal data class VerifyScoreCell(val title: String, val value: String, val segments: List<VerifyScoreSegment>, val targetKey: String, val note: String = "")
internal data class VerifyOutcomeView(val title: String, val verdict: String?, val message: String, val tone: VerifyScoreTone, val qaLowered: Boolean, val cells: List<VerifyScoreCell>, val headline: String = "", val elapsed: String? = null, val startedAt: Instant? = null, val finishedAt: Instant? = null, val state: VerifyRunState = VerifyRunState.Pending)

internal fun verifyOutcomeView(run: VerifyRunDetails): VerifyOutcomeView {
  val state = verifyRunState(run)
  val steps = auditedSteps(run)
  val index = when (state) {
    VerifyRunState.Stopped, VerifyRunState.Superseded -> stoppedStepIndex(run)
    else -> steps.indexOfFirst { it.status == "running" }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.stepId == run.currentStepId }.coerceAtLeast(0)
  }
  val step = steps.getOrNull(index)
  val grades = run.completenessAudit?.grades.orEmpty()
  val findings = run.verdict?.findings.orEmpty()
  val warning = findings.any { it.startsWith("Warning:") }
  val message = when (state) {
    VerifyRunState.Pending -> "Verify has not started collecting inputs."
    VerifyRunState.Running -> if (step?.stepId == "code_review" && run.changedFiles.isNotEmpty()) "Specialist reviewers are reading ${run.changedFiles.size} changed files. Results from earlier steps are below." else "Results from earlier steps are below."
    VerifyRunState.Parked -> if (run.criteria.isEmpty()) "Criteria extraction found no numbered acceptance criteria, so a confirmed run would audit nothing. Adjust the intake with the criteria pasted in, or point it at a spec file." else "Verify extracted ${run.criteria.size} acceptance criteria. Check them before the review and audit start; the audit grades the code against exactly this list."
    VerifyRunState.Superseded -> "Abandoned while the criteria were waiting for confirmation."
    VerifyRunState.Stopped -> {
      val why = when {
        run.staleReason != null -> "The run made no progress and was marked stale."
        run.workflowStatus == "failed" -> "The run failed before a verdict was recorded."
        else -> "You stopped the run."
      }
      "$why Finished steps keep their results below; resuming starts again at ${step?.let { verifyStepName(it.stepId).lowercase() } ?: "the last step"}."
    }
    VerifyRunState.Finished -> "Verification finished. See Steps for findings and evidence."
  }
  fun hasRun(stepId: String): Boolean = run.steps.firstOrNull { it.stepId == stepId }?.status in setOf("completed", "skipped")
  fun cell(title: String, value: String, segments: List<VerifyScoreSegment>, stepId: String, key: String = verifyStepKey(stepId)) =
    VerifyScoreCell(title, value, if (hasRun(stepId)) segments else emptyList(), key)
  val criterionGrades = run.criteria.mapNotNull { criterion -> grades.firstOrNull { it.number == criterion.number } }
  val ungradedCriteria = (run.criteria.size - criterionGrades.size).coerceAtLeast(0)
  val criteriaSegments = listOf(
    VerifyScoreSegment(criterionGrades.count { it.met }, VerifyScoreTone.Green),
    VerifyScoreSegment(criterionGrades.count { it.gap && it.status == "PARTIAL" }, VerifyScoreTone.Amber),
    VerifyScoreSegment(criterionGrades.count { it.gap && it.status == "FAIL" }, VerifyScoreTone.Red),
    VerifyScoreSegment(ungradedCriteria, VerifyScoreTone.Faint),
  ).filter { it.count > 0 }
  val review = run.reviewFindings
  val openReviewItems = review?.items.orEmpty().filter { it.open }
  val reviewFindingCount = review?.findings?.size ?: 0
  val knownReviewSeverityCount = review?.items?.count {
    it.severity.equals("nit", true) || it.severity.equals("minor", true) ||
      it.severity.equals("major", true) || it.severity.equals("blocker", true)
  } ?: 0
  val reviewSegments = when {
    review == null -> emptyList()
    reviewFindingCount == 0 -> listOf(VerifyScoreSegment(1, VerifyScoreTone.Green))
    else -> listOf(
      VerifyScoreSegment(review.items.size - openReviewItems.size, VerifyScoreTone.Green),
      VerifyScoreSegment(openReviewItems.count { it.severity.equals("nit", true) }, VerifyScoreTone.Faint),
      VerifyScoreSegment(openReviewItems.count { it.severity.equals("minor", true) }, VerifyScoreTone.Amber),
      VerifyScoreSegment(openReviewItems.count { it.severity.equals("major", true) || it.severity.equals("blocker", true) }, VerifyScoreTone.Red),
      VerifyScoreSegment((reviewFindingCount - knownReviewSeverityCount).coerceAtLeast(0), VerifyScoreTone.Faint),
    ).filter { it.count > 0 }
  }
  val tests = run.unitTestValue
  val testSegments = listOf(
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Valuable", true) } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Weak", true) } ?: 0, VerifyScoreTone.Amber),
    VerifyScoreSegment(tests?.rows?.count { it.verdict.equals("Harmful", true) } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val qaSelected = run.qaSelection == "on"
  val qaSegments = listOf(
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.Passed } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.NotExercised } ?: 0, VerifyScoreTone.Amber),
    VerifyScoreSegment(run.qa?.assertions?.count { it.outcome == QaAssertionOutcome.Failed } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val flagSegments = listOf(
    VerifyScoreSegment(run.flagAudit?.checks?.count { it.passed } ?: 0, VerifyScoreTone.Green),
    VerifyScoreSegment(run.flagAudit?.checks?.count { !it.passed } ?: 0, VerifyScoreTone.Red),
  ).filter { it.count > 0 }
  val criteriaValue =
    if (run.completenessAudit == null) "${run.criteria.size} extracted"
    else "${criterionGrades.count { it.status.equals("PASS", true) }} / ${run.criteria.size} met"
  val testsValue = when {
    tests == null -> "—"
    tests.verdict.equals("skipped", true) -> "skipped"
    !tests.overall.isNullOrBlank() -> tests.overall.orEmpty()
    else -> "unavailable"
  }
  val flag = run.flagAudit
  val qa = run.qa
  val flagValue = when {
    flag == null -> "—"
    !flag.verdict.isNullOrBlank() -> flag.verdict.orEmpty()
    !flag.flagName.isNullOrBlank() -> flag.flagName.orEmpty()
    else -> "unavailable"
  }
  val qaValue = when {
    qa == null && run.qaVerdict?.equals("skipped", true) == true -> "skipped"
    qa == null && run.qaVerdict?.equals("passed", true) == true -> "unavailable"
    qa == null -> run.qaVerdict ?: "pending"
    qa.findings.isNotEmpty() -> "${qa.findings.size} open"
    run.qaVerdict.equals("passed", true) -> "passed"
    else -> run.qaVerdict ?: "unavailable"
  }
  val cells = if (state == VerifyRunState.Parked || state == VerifyRunState.Superseded) emptyList() else listOf(
    cell("Criteria", criteriaValue, if (run.completenessAudit == null) emptyList() else criteriaSegments, "completeness_audit", VERIFY_CRITERIA_KEY),
    cell("Review", review?.let { "$reviewFindingCount ${if (reviewFindingCount == 1) "finding" else "findings"}" } ?: "—", reviewSegments, "code_review"),
    cell("Tests", testsValue, testSegments, "unit_test_value_check"),
    if (qaSelected) cell("QA", qaValue, qaSegments, "qa", VERIFY_QA_KEY)
    else cell("Flag audit", flagValue, flagSegments, "feature_flag_audit"),
  )
  val verdict = run.verdict?.verdict.orEmpty()
  val tone = when (verdict.uppercase()) {
    "APPROVE" -> VerifyScoreTone.Green
    "APPROVE WITH FIXES" -> VerifyScoreTone.Amber
    "REQUEST CHANGES" -> VerifyScoreTone.Red
    else -> VerifyScoreTone.Faint
  }
  val title = when (state) {
    VerifyRunState.Finished -> "Verdict"
    VerifyRunState.Running -> "Step ${index + 1} of ${steps.size}"
    VerifyRunState.Parked -> "Step 2 of ${steps.size} · waiting for you"
    VerifyRunState.Stopped, VerifyRunState.Superseded -> "No verdict · stopped at step ${index + 1} of ${steps.size}"
    VerifyRunState.Pending -> ""
  }
  val headline = when (state) {
    VerifyRunState.Pending -> "Waiting to start"
    VerifyRunState.Parked -> if (run.criteria.isEmpty()) "No criteria to audit" else "Confirm the criteria"
    VerifyRunState.Running, VerifyRunState.Stopped, VerifyRunState.Superseded -> step?.let { verifyStepName(it.stepId) }.orEmpty()
    VerifyRunState.Finished -> verdict
  }
  val stateTone = when (state) {
    VerifyRunState.Running -> VerifyScoreTone.Active
    VerifyRunState.Parked -> VerifyScoreTone.Amber
    VerifyRunState.Stopped -> VerifyScoreTone.Red
    VerifyRunState.Finished -> tone
    else -> VerifyScoreTone.Faint
  }
  val notes = verifyScoreNotes(run)
  val elapsed = run.startedAt?.let { start -> run.finishedAt?.let { activityDuration((it - start).inWholeMilliseconds.coerceAtLeast(0)) } }
  return VerifyOutcomeView(title, verdict.takeIf { state == VerifyRunState.Finished && it.isNotBlank() }, message, stateTone, state == VerifyRunState.Finished && warning, cells.map { it.copy(note = notes[it.title].orEmpty()) }, headline, elapsed, run.startedAt, run.finishedAt, state)
}
