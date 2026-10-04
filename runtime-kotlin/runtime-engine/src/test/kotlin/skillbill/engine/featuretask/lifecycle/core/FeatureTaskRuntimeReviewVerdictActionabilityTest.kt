package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeReviewVerdictActionabilityTest {
  @Test
  fun `a refuted major does not force changes_requested when only minors remain actionable`() {
    val envelope =
      mapOf(
        "verdict" to "approved",
        "produced_outputs" to
          mapOf(
            "findings" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "severity" to "major",
                  "message" to "refuted major that must not reopen review_fix",
                  "claim_verdict" to "refuted",
                ),
                mapOf(
                  "finding_id" to "F-002",
                  "severity" to "minor",
                  "message" to "advisory only",
                  "claim_verdict" to "confirmed",
                  "scope_disposition" to "in_scope",
                ),
              ),
          ),
      ).toWorkflowArtifactMap()

    assertEquals(
      FeatureTaskRuntimeVerdict.APPROVED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        envelope,
        FeatureTaskRuntimeOutputVerification.reviewVerdictRule,
      ),
    )
    assertEquals(
      emptyList(),
      FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(envelope),
    )
  }

  @Test
  fun `a confirmed major still opens remediation`() {
    val envelope =
      mapOf(
        "verdict" to "approved",
        "produced_outputs" to
          mapOf(
            "findings" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "severity" to "major",
                  "message" to "still actionable",
                  "claim_verdict" to "confirmed",
                  "scope_disposition" to "in_scope",
                ),
              ),
          ),
      ).toWorkflowArtifactMap()

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        envelope,
        FeatureTaskRuntimeOutputVerification.reviewVerdictRule,
      ),
    )
    assertEquals(
      1,
      FeatureTaskRuntimeOutputVerification.unresolvedReviewFindings(envelope).size,
    )
  }

  @Test
  fun `findings without claim_verdict stay severity-driven for legacy envelopes`() {
    val envelope =
      mapOf(
        "produced_outputs" to
          mapOf(
            "findings" to
              listOf(
                mapOf(
                  "finding_id" to "F-001",
                  "severity" to "major",
                  "message" to "legacy major without adjudication overlay",
                ),
              ),
          ),
      ).toWorkflowArtifactMap()

    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeOutputVerification.verdictFor(
        envelope,
        FeatureTaskRuntimeOutputVerification.reviewVerdictRule,
      ),
    )
  }
}
