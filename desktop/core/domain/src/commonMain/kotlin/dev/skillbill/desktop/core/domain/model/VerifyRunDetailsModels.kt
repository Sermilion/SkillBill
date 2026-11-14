package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

data class VerifyRunDetails(
  val workflowId: String, val issueKey: String?, val workflowStatus: String, val currentStepId: String?,
  val startedAt: Instant?, val finishedAt: Instant?, val intake: String?, val target: String?, val repoRoot: String?,
  val baseRevision: String?, val headRevision: String?, val reviewMode: String?, val qaSelection: String?, val specPath: String?,
  val steps: List<VerifyRunStep>, val criteria: List<VerifyRunCriterion>, val criteriaRaw: String?, val nonGoals: List<String>,
  val technicalConstraints: List<String>, val rolloutExpectation: String?, val completenessAudit: VerifyCompletenessAudit?,
  val reviewFindings: VerifyReviewSection?, val flagAudit: VerifyFlagAudit?, val unitTestValue: VerifyUnitTestValue?,
  val changedFiles: List<String>, val verdict: VerifyRunVerdict?, val qa: QaRunDetails?, val qaVerdict: String?, val qaOutcome: String?,
  val qaFindingLocations: Map<String, List<String>>, val supersededBy: String?, val staleReason: String?,
  val qaReportText: String? = null,
  val operatorFinalizedAt: Instant? = null,
)
data class VerifyRunStep(val stepId: String, val status: String, val attemptCount: Int)
data class VerifyRunCriterion(val number: Int, val text: String)
data class VerifyCompletenessAudit(val verdict: String?, val findings: List<String>, val grades: List<VerifyCriterionGrade>)
data class VerifyCriterionGrade(val number: Int, val status: String, val text: String, val evidence: List<String>, val missing: String?, val notFound: String?, val locations: List<String>, val resolution: VerifyCriterionResolution? = null) {
  val met: Boolean get() = status == "PASS" || resolution != null
  val gap: Boolean get() = resolution == null && (status == "PARTIAL" || status == "FAIL")
}
data class VerifyCriterionResolution(val evidence: String, val recordedAt: Instant)
data class VerifyReviewSection(val verdict: String?, val findings: List<String>, val items: List<VerifyReviewFinding>, val reviewRunId: String? = null)
data class VerifyReviewFinding(val ref: String, val severity: String, val confidence: String, val location: String, val description: String, val specialists: List<String>, val locations: List<String>, val status: ReviewFindingStatus? = null) {
  val open: Boolean get() = status == null || status == ReviewFindingStatus.Open || status == ReviewFindingStatus.Accepted
}
data class VerifyFlagAudit(val verdict: String?, val findings: List<String>, val skipReason: String?, val flagName: String?, val pattern: String?, val issues: String?, val checks: List<VerifyFlagCheck>)
data class VerifyFlagCheck(val passed: Boolean, val text: String)
data class VerifyUnitTestValue(val verdict: String?, val findings: List<String>, val skipReason: String?, val overall: String?, val summary: String?, val rows: List<VerifyTestValueRow>, val keep: List<String>, val rewrite: List<String>, val delete: List<String>, val missingCases: List<String>)
data class VerifyTestValueRow(val area: String, val verdict: String, val confidence: String, val files: List<String>, val why: String, val action: String, val locations: List<String>)
data class VerifyRunVerdict(val verdict: String?, val findings: List<String>, val summary: String?)
