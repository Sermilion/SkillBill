package skillbill.engine.featuretask.review.finding

import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.runner.disposition
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeFindingVerificationDispositionFromArtifact
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeFindingVerificationOutputTest {
  @Test
  fun `verify_findings wire verdict settles findings_verified`() {
    val verdict =
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf(
          "verdict" to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED.wireValue,
          "produced_outputs" to
            mapOf(
              "finding_dispositions" to
                listOf(
                  mapOf(
                    "finding_id" to "F-001",
                    "disposition" to "verified",
                  ),
                ),
            ),
        ).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict settles no_findings_verified`() {
    val verdict =
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf(
          "verdict" to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED.wireValue,
          "produced_outputs" to
            mapOf(
              "finding_dispositions" to
                listOf(
                  mapOf(
                    "finding_id" to "F-001",
                    "disposition" to "rejected",
                  ),
                ),
            ),
        ).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )
    assertEquals(FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict findings_verified settles when census has zero verified rows`() {
    val verdict =
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf(
          "verdict" to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED.wireValue,
          "produced_outputs" to
            mapOf(
              "finding_dispositions" to
                listOf(
                  mapOf(
                    "finding_id" to "F-001",
                    "disposition" to "rejected",
                  ),
                ),
            ),
        ).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings wire verdict no_findings_verified settles when census has verified rows`() {
    val verdict =
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf(
          "verdict" to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED.wireValue,
          "produced_outputs" to
            mapOf(
              "finding_dispositions" to
                listOf(
                  mapOf(
                    "finding_id" to "F-001",
                    "disposition" to "verified",
                  ),
                ),
            ),
        ).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )
    assertEquals(FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings without wire verdict derives the verdict from its dispositions`() {
    val verdict =
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf(
          "produced_outputs" to
            mapOf(
              "finding_dispositions" to
                listOf(
                  mapOf(
                    "finding_id" to "F-001",
                    "disposition" to "verified",
                  ),
                ),
            ),
        ).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )

    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, verdict)
  }

  @Test
  fun `verify_findings without wire verdict or dispositions loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeOutputVerification.verdictFor(
        mapOf("produced_outputs" to mapOf("value" to "Checked the findings.")).toWorkflowArtifactMap(),
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule,
      )
    }
  }

  @Test
  fun `optional reason round-trips through artifact map`() {
    val disposition =
      FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = "F-001",
        disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
        reason = "Matches spec intent.",
      )
    assertEquals(
      disposition,
      decodeFindingVerificationDispositionFromArtifact(
        disposition.asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        "finding_dispositions[0]",
      ),
    )
  }

  @Test
  fun `census-only disposition ignores extra keys`() {
    val disposition =
      requireNotNull(
        decodeFindingVerificationDispositionFromArtifact(
          mapOf(
            "finding_id" to "F-001",
            "disposition" to "verified",
            "severity" to "major",
            "location" to "Example.kt",
            "message" to "Finding",
          ),
          "finding_dispositions[0]",
        ),
      )
    assertEquals("F-001", disposition.findingId)
    assertNull(disposition.reason)
  }

  @Test
  fun `malformed finding verification checkpoint loud-fails when raw is not an array`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
        FeatureTaskRuntimeFindingVerificationDisposition.parseList(
          mapOf("finding_id" to "F-001"),
          "finding_verification_checkpoint",
        )
      }
    assertTrue(error.reason.contains("finding_verification_checkpoint"))
    assertTrue(error.reason.contains("array"))
  }

  @Test
  fun `retired disposition field loud-fails with named verification record error`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
        decodeFindingVerificationDispositionFromArtifact(
          mapOf(
            "finding_id" to "F-001",
            "verdict" to "verified",
          ),
          "finding_verification_checkpoint[0]",
        )
      }
    assertTrue(error.reason.contains("disposition"))
  }
}
