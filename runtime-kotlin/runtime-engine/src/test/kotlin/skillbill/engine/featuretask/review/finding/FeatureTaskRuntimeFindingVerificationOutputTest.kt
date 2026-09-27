package skillbill.engine.featuretask.review.finding

import skillbill.engine.disposition
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCensusCoverageTestSupport.assertVerifyCoverageContains
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCensusCoverageTestSupport.parseVerifyDispositions
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCensusCoverageTestSupport.verifyDisposition
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeFindingVerificationDispositionFromArtifact
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.model.validation.validateDispositionCoverage
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
  fun `verify_findings without wire verdict loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
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
    }
  }

  @Test
  fun `validateDispositionCoverage rejects duplicate finding ids`() {
    val dispositions =
      parseVerifyDispositions(
        listOf(
          verifyDisposition("F-001", "verified"),
          verifyDisposition("F-001", "rejected"),
        ),
      )
    assertVerifyCoverageContains(dispositions, setOf("F-001", "F-002"), "duplicate finding_id: F-001.")
  }

  @Test
  fun `validateDispositionCoverage accepts empty review and empty dispositions`() {
    assertNull(validateDispositionCoverage(emptyList(), emptySet()))
  }

  @Test
  fun `validateDispositionCoverage rejects foreign disposition when review findings are empty`() {
    val dispositions = parseVerifyDispositions(listOf(verifyDisposition("F-001")))
    assertVerifyCoverageContains(dispositions, emptySet(), "absent from the preceding review pass: F-001")
  }

  @Test
  fun `finding verification disposition coverage rejects omitted review findings`() {
    val dispositions = parseVerifyDispositions(listOf(verifyDisposition("F-001")))
    assertVerifyCoverageContains(dispositions, setOf("F-001", "F-002"), "omitted finding_id: F-002")
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
