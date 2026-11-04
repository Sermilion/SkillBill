package skillbill.goalrunner.assessment.model

import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity

enum class GoalAssessmentRole(val wireValue: String) {
  SUBTASK("subtask"),
  CROSS_SUBTASK("cross_subtask"),
  VERIFIER("verifier"),
}

enum class GoalAssessmentRunStatus(val wireValue: String) {
  COMPLETED("completed"),
  FAILED("failed"),
  MALFORMED("malformed"),
}

enum class GoalAssessmentFindingKind(val wireValue: String) {
  DEFECT("defect"),
  TEST_VALUE("test_value"),
}

enum class GoalAssessmentVerification(val wireValue: String) {
  CONFIRMED("confirmed"),
  REFUTED("refuted"),
  UNVERIFIED("unverified"),
  NOT_REQUIRED("not_required"),
}

enum class GoalAssessmentDisposition(val wireValue: String) {
  FIX("fix"),
  PR_ONLY("pr_only"),
}

enum class GoalAssessmentFindingState(val wireValue: String) {
  OPEN("open"),
  FIXED("fixed"),
  REFUTED("refuted"),
}

enum class GoalAssessmentScenarioVerdict(val wireValue: String) {
  HOLDS("holds"),
  BROKEN("broken"),
}

enum class GoalAssessmentRegressionState(val wireValue: String) {
  RESTORED("restored"),
  NOT_RESTORED("not_restored"),
}

enum class GoalAssessmentFixStatus(val wireValue: String) {
  COMPLETED("completed"),
  NO_CHANGE("no_change"),
  FAILED("failed"),
  NOT_RUN("not_run"),
}

enum class GoalAssessmentFixValidation(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  NOT_RUN("not_run"),
}

enum class GoalAssessmentExitReason(val wireValue: String) {
  CONVERGED("converged"),
  ROUND_CAP("round_cap"),
  PERSISTENT_FINDING("persistent_finding"),
  ASSESSMENT_UNAVAILABLE("assessment_unavailable"),
  SKIPPED_NO_CHANGES("skipped_no_changes"),
}

data class GoalAssessmentRun(
  val role: GoalAssessmentRole,
  val subtaskId: Int?,
  val status: GoalAssessmentRunStatus,
)

data class GoalAssessmentFinding(
  val findingRef: String,
  val kind: GoalAssessmentFindingKind,
  val severity: FeatureTaskRuntimeReviewSeverity,
  val path: String,
  val line: Int,
  val failureScenario: String,
  val guardedRef: String?,
  val subtaskId: Int?,
  val originRound: Int,
  val verification: GoalAssessmentVerification,
  val verificationEvidence: String,
  val fixRoundsSurvived: Int,
  val disposition: GoalAssessmentDisposition,
  val state: GoalAssessmentFindingState,
)

data class GoalAssessmentScenarioResult(
  val scenarioRef: String,
  val verdict: GoalAssessmentScenarioVerdict,
  val evidence: String,
)

data class GoalAssessmentRegressionResult(
  val regressionRef: String,
  val state: GoalAssessmentRegressionState,
  val evidence: String,
)

data class GoalAssessmentFix(
  val status: GoalAssessmentFixStatus,
  val changedFiles: List<String>,
  val commitSha: String?,
  val validation: GoalAssessmentFixValidation,
)

data class GoalAssessmentRound(
  val round: Int,
  val headSha: String,
  val scopeFiles: List<String>,
  val assessorRuns: List<GoalAssessmentRun>,
  val findings: List<GoalAssessmentFinding>,
  val scenarios: List<GoalAssessmentScenarioResult>,
  val regressions: List<GoalAssessmentRegressionResult>,
  val fix: GoalAssessmentFix?,
  val targetRef: String? = null,
  val repairPlan: String? = null,
)

data class GoalAssessmentRecord(
  val issueKey: String,
  val rounds: List<GoalAssessmentRound> = emptyList(),
  val exitReason: GoalAssessmentExitReason? = null,
  val openFindingRefs: List<String> = emptyList(),
  val budgetStartRound: Int = 1,
)

data class GoalAssessmentRecordIssue(val fieldPath: String, val reason: String)
