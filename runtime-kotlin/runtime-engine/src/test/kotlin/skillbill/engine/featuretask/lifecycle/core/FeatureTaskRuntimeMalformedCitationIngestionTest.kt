package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.engine.envelope
import skillbill.engine.featuretask.slot.codereview.InlineReviewCycle
import skillbill.engine.featuretask.slot.codereview.InlineReviewEnvelope
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnostic
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeMalformedCitationIngestionTest {
  @Test
  fun `review envelope assembly keeps surviving findings and records citation diagnostics`() {
    val mergeResult =
      ParallelReviewMergeResult(
        findings =
          listOf(
            ParallelReviewMergedFinding(
              fNumber = "F-001",
              agentIds = listOf("codex"),
              severity = ParallelReviewSeverity.MAJOR,
              confidence = "High",
              location = "src/A.kt:12",
              description = "Issue remains visible",
              repositoryPath = "src/A.kt",
              line = 12,
              citations = listOf(ReviewFindingCitation("src/A.kt", 12)),
            ),
          ),
        formattedOutput = "Review completed.",
      )
    val result =
      ParallelCodeReviewResult(
        mergeResult = mergeResult,
        lane1 = ParallelReviewLaneStatus(agentId = "codex", success = true),
        citationDiagnostics =
          listOf(
            ReviewFindingCitationDiagnostic(
              citationIndex = 1,
              path = "src/A.kt",
              rawLine = "0",
              reason = "non_positive_line",
            ).withFindingRef("F-001"),
          ),
      )
    val outputText =
      InlineReviewEnvelope.assemble(
        result = result,
        reviewRunId = "rvw-malformed-citation",
        cycle =
          InlineReviewCycle(
            passNumber = 1,
            resolvedTier = CodeReviewExecutionMode.INLINE,
            repositoryFingerprint = "checkpoint-1",
          ),
      )
    val envelope = InlineReviewEnvelope.envelopeMap(outputText)
    val findings = GoalSubtaskReviewStructuredFindingsParse.structuredFindings(envelope)
    assertEquals(1, findings.size)
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      GoalSubtaskReviewSummaryReducer.outcomeFor(envelope).verdict,
    )
    val produced = envelope["produced_outputs"] as Map<*, *>
    val diagnostics = produced[FeatureTaskRuntimeVerificationSignalKeys.CITATION_DIAGNOSTICS] as List<*>
    assertEquals(1, diagnostics.size)
    val diagnostic = diagnostics.single() as Map<*, *>
    assertEquals("F-001", diagnostic["finding_ref"])
    assertEquals("non_positive_line", diagnostic["reason"])
  }

  @Test
  fun `structured findings parse does not abort when review findings carry malformed citation lines`() {
    val output =
      mapOf(
        "produced_outputs" to
          mapOf(
            FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "severity" to "major",
                  "message" to "Malformed citation must not abort ingestion",
                  "location" to "src/A.kt:12",
                  "citations" to
                    listOf(
                      mapOf("path" to "src/A.kt", "line" to "nope"),
                    ),
                ),
              ),
          ),
      )
    val parsed = GoalSubtaskReviewStructuredFindingsParse.parseStructuredFindings(output)
    assertEquals(1, parsed.findings.size)
    assertEquals(emptyList(), parsed.findings.single().citations)
    assertNotNull(parsed.citationDiagnostics.single().diagnostic.reason)
  }
}
