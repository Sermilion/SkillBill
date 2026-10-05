package skillbill.goalrunner.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFindingsParse
import skillbill.review.model.RecordedVerdictFields
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.repositoryRelativePathViolation
import skillbill.review.parsing.ReviewFindingActionability
import skillbill.review.parsing.ReviewFindingFieldCodec

object GoalSubtaskReviewStructuredFindingsParse {
  fun structuredFindings(
    output: Any,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<StructuredGoalReviewFinding> = parseStructuredFindings(output, recordedVerdicts).findings

  fun parseStructuredFindings(
    output: Any,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): StructuredGoalReviewFindingsParse {
    val wire = output.asGoalSubtaskReviewPhaseOutputMap()
    val findings =
      wire[SharedPayloadKeys.PRODUCED_OUTPUTS]
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(ReviewVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
        ?: return StructuredGoalReviewFindingsParse(emptyList(), emptyList())
    val citationDiagnostics = mutableListOf<ReviewFindingCitationDiagnosticWithFinding>()
    val parsed =
      findings.mapNotNull { entry ->
        val finding = JsonCodec.anyToStringAnyMap(entry) ?: return@mapNotNull null
        val severity =
          (finding["severity"] as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val message =
          (finding["message"] as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val findingRef =
          ReviewFindingFieldCodec.findingRefOf(
            finding["id"],
            finding[ReviewFindingPayloadKeys.FINDING_ID],
            finding[ReviewFindingPayloadKeys.F_NUMBER],
          )
        val decodedCitations = ReviewFindingFieldCodec.decodeCitations(finding[ReviewFindingPayloadKeys.CITATIONS])
        decodedCitations.diagnostics.forEach { diagnostic ->
          citationDiagnostics += diagnostic.withFindingRef(findingRef)
        }
        val overlay =
          ReviewFindingActionability.overlayOf(
            findingRef = findingRef,
            recordedVerdicts = recordedVerdicts,
            encoded =
              RecordedVerdictFields(
                claimVerdict = ReviewFindingFieldCodec.claimVerdictOf(finding[ReviewFindingPayloadKeys.CLAIM_VERDICT]),
                scopeDisposition =
                  ReviewFindingFieldCodec.scopeDispositionOf(
                    finding[ReviewFindingPayloadKeys.SCOPE_DISPOSITION],
                  ),
                citations = decodedCitations.citations,
                severityAdjustment =
                  ReviewFindingFieldCodec.severityAdjustmentOf(
                    finding[ReviewFindingPayloadKeys.SEVERITY_ADJUSTMENT],
                  ),
              ),
          )
        StructuredGoalReviewFinding(
          severity = severity,
          message = message,
          issueCategory =
            sequenceOf(finding[ReviewFindingPayloadKeys.ISSUE_CATEGORY], finding["category"])
              .filterIsInstance<String>().firstOrNull()?.trim()?.lowercase() ?: "other",
          location =
            sequenceOf(finding["location"], finding[ReviewFindingPayloadKeys.ARTIFACT_REF])
              .filterIsInstance<String>().firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "<unknown>",
          compactLabel = GoalSubtaskReviewSummarySanitize.labelFor(finding, message),
          findingId = findingRef,
          repositoryPath = admissibleRepositoryPath(finding[ReviewFindingPayloadKeys.REPOSITORY_PATH] as? String),
          claimVerdict = overlay.claimVerdict,
          scopeDisposition = overlay.scopeDisposition,
          citations = overlay.citations,
          severityAdjustment = overlay.severityAdjustment,
        )
      }
    return StructuredGoalReviewFindingsParse(parsed, citationDiagnostics)
  }

  fun citationDiagnostics(
    output: Any,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<ReviewFindingCitationDiagnosticWithFinding> =
    parseStructuredFindings(output, recordedVerdicts).citationDiagnostics

  internal fun reviewRunIdOf(output: Any): String? =
    (
      output.asGoalSubtaskReviewPhaseOutputMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID) as? String
    )?.trim()?.takeIf(String::isNotBlank)

  fun recordedVerdicts(
    fetchFindingVerdicts: (String) -> List<ReviewFindingVerdict>,
    output: Any,
  ): List<ReviewFindingVerdict> {
    val reviewRunId = reviewRunIdOf(output) ?: return emptyList()
    return fetchFindingVerdicts(reviewRunId)
  }

  fun verificationBoundaryFindingPaths(finding: StructuredGoalReviewFinding): List<String> {
    val paths = mutableListOf<String>()
    finding.repositoryPath?.let { paths += it }
    finding.citations.map(ReviewFindingCitation::path).filter { it.isNotBlank() }.forEach { paths += it }
    pathFromLocationLine(finding.location)?.let { paths += it }
    return paths.distinct()
  }

  private fun admissibleRepositoryPath(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    return trimmed.takeIf { repositoryRelativePathViolation(it) == null }
  }

  private fun pathFromLocationLine(location: String): String? {
    val token = location.trim()
    if (token.isBlank() || token == "<unknown>") return null
    val colon = token.lastIndexOf(':')
    val candidate =
      if (colon > 0) {
        val line = token.substring(colon + 1).trim().toIntOrNull()
        if (line != null && line >= 1) {
          token.substring(0, colon).trim().takeIf(String::isNotBlank)
        } else {
          token
        }
      } else {
        token
      }
    return admissibleRepositoryPath(candidate)
  }
}
