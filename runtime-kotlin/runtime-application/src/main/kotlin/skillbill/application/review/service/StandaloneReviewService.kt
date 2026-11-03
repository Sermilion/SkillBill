package skillbill.application.review.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ImportedReviewResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewFindingStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

@Inject
class StandaloneReviewService(private val database: DatabaseSessionFactory) {
  fun prepareFindings(invocationId: String): ImportedReviewResult? =
    database.transaction { unit ->
      val record = unit.standalonePhaseStatuses.readByInvocation(invocationId) ?: return@transaction null
      val review = savedReview(record) ?: return@transaction null
      val existing = unit.reviews.fetchFindingStatuses(review.reviewRunId)
      val refs = review.findings.map { it.findingId }.toSet()
      if (existing.isNotEmpty() && existing.keys != refs) return@transaction null
      if (existing.isEmpty()) {
        unit.reviews.saveImportedReview(review, sourcePath = null)
        refutedRefs(record).takeIf { it.isNotEmpty() }?.let { refuted ->
          unit.reviews.recordFeedback(
            FeedbackRequest(review.reviewRunId, refuted, FALSE_POSITIVE_EVENT, VERIFIER_REFUTATION_NOTE),
            FeedbackTelemetryOptions(),
          )
        }
      }
      review.toImportedReviewResult(dbPath = unit.dbPath.toString())
    }
}

private fun refutedRefs(record: StandalonePhaseStatusRecord): List<String> =
  StandaloneReviewReportReader.read(record.acceptedResult ?: record.terminalResult)
    ?.findings
    ?.filter { it.status == ReviewFindingStatus.FALSE_POSITIVE }
    ?.map { it.ref }
    .orEmpty()

private fun savedReview(record: StandalonePhaseStatusRecord): ImportedReview? {
  val runId = record.reviewRunId
  val text = record.acceptedResult ?: record.terminalResult.orEmpty()
  if (record.phaseId != SkeletonDefinition.REVIEW.id || record.finishedAt == null || runId == null) {
    return null
  }
  val reportText = text.takeUnless { record.acceptedResult == null && it.endsWith("...") }

  val report = StandaloneReviewReportReader.read(reportText) ?: return null
  if (report.findings.isEmpty() || report.findings.map { it.ref }.distinct().size != report.findings.size) return null
  return ImportedReview(
    reviewRunId = runId,
    reviewSessionId = record.invocationId,
    rawText = text,
    routedSkill = null,
    detectedScope = null,
    detectedStack = null,
    executionMode = null,
    specialistReviews = report.lanes,
    findings =
      report.findings.map { finding ->
        ImportedFinding(
          findingId = finding.ref,
          severity = finding.severity,
          confidence = finding.confidence.orEmpty(),
          location = finding.location.orEmpty(),
          description = finding.description,
          findingText = finding.description,
          laneSkillName = finding.specialist,
        )
      },
  )
}

private const val FALSE_POSITIVE_EVENT = "false_positive"
private const val VERIFIER_REFUTATION_NOTE = "Rejected by the standalone review verifier."
