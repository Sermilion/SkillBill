package skillbill.workflow.taskruntime.model.blockincident.report

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentInvestigatorKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductReportSanitizerFrameTest {
  @Test
  fun removesForeignFramesFromEveryFreeTextFieldAndIncidentComments() {
    listOf(
      "other+skillbill.Helper.call:42",
      "other+skillbill.Helper.call(Unknown Source).",
      "other skillbill.Helper.load value:42.",
      "other\nskillbill.Helper.load\nvalue(Unknown Source).",
    ).forEach { frame ->
      listOf(null, "bi_${"a".repeat(64)}").forEach { commentToken ->
        val report = assertIs<ProductReportDraft>(sanitize(frame, commentToken = commentToken))

        assertFalse(report.body.contains("Helper"), report.body)
        assertFalse(report.title.contains("Helper"), report.title)
        textFields.forEach { key ->
          assertEquals(ProductReportFieldOutcome.REDACTED, report.fieldOutcomes[key], key)
        }
        assertTrue(report.body.contains("cause: Suspected: [redacted]"), report.body)
        assertTrue(report.body.toByteArray(Charsets.UTF_8).size <= ProductReportSanitizer.MAX_BODY_BYTES)
        val payload = assertNotNull(ProductReportSanitizer.createPayload(report, "bir_test"))
        assertFalse(payload.body.contains("Helper"), payload.body)
      }
    }
  }

  @Test
  fun keepsSkillBillFramesWithoutTreatingRenderedFieldLabelsAsDeclaringClasses() {
    listOf("skillbill.Helper.call:42", "skillbill.Helper+Generated.load\nvalue(Unknown Source).")
      .forEach { frame ->
        val report = assertIs<ProductReportDraft>(sanitize(frame))

        assertTrue(report.title.contains(frame), report.title)
        assertTrue(report.body.contains("cause: Suspected: $frame"), report.body)
        assertTrue(report.body.contains("original_failure: $frame"), report.body)
        assertTrue(report.body.contains("$frame: resumed at 2026-10-21T12:01:00Z"), report.body)
        textFields.forEach { key ->
          assertEquals(ProductReportFieldOutcome.KEPT, report.fieldOutcomes[key], key)
        }
        val payload = assertNotNull(ProductReportSanitizer.createPayload(report, "bir_test"))
        assertTrue(payload.body.contains("cause: Suspected: $frame"), payload.body)
      }
  }

  @Test
  fun rejectsResidualSecretsAndForeignFramesInTheRenderedIncidentMarker() {
    listOf("token=canary", "other+skillbill.Helper.call:42", "other.Helper.call(Unknown Source).")
      .forEach { commentToken ->
        val report = assertIs<ProductReportRejected>(sanitize("Failure", commentToken = commentToken))

        assertEquals(ProductReportFailure.RESIDUAL_SECRET, report.reason)
      }
  }

  private fun sanitize(
    value: String,
    commentToken: String? = null,
  ): ProductReportSanitization {
    val token = "bi_${"a".repeat(64)}"
    val observed = Instant.parse("2026-10-21T12:00:00Z")
    val occurrence =
      BlockIncidentOccurrence(
        token,
        BlockIncidentScope(workflowId = "wf-1", phaseId = "implement"),
        BlockIncidentFailure(
          origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
          reason = "blocked",
          reasonRetention = BlockIncidentReasonRetention.COMPLETE,
          failureCode = value,
          exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
        ),
        observed,
        BlockIncidentEvidenceAvailability.DATABASE,
        BlockIncidentIdentityAvailability.AVAILABLE,
        emptyMap(),
      )
    val diagnosis =
      BlockIncidentDiagnosisRecord(
        token,
        1,
        value,
        value,
        value,
        BlockIncidentCertainty.SUSPECTED,
        null,
        BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT,
        listOf(
          BlockIncidentDiagnosisEvidence(
            BlockIncidentDiagnosisEvidenceKind.ORIGINAL_FAILURE,
            "reference",
            value,
            "b".repeat(64),
            true,
          ),
        ),
        emptyList(),
        BlockIncidentRecoveryAction.NONE,
        null,
        false,
        emptySet(),
        BlockIncidentInvestigatorKind.DETERMINISTIC,
        observed.plusSeconds(60),
        reproductionSteps = value,
      )
    return ProductReportSanitizer.sanitize(
      occurrence,
      diagnosis,
      listOf(recoveryAttempt(value, token, observed)),
      ProductReportRuntimeFacts(value, value, value, value, value, value),
      commentToken,
    )
  }

  private fun recoveryAttempt(
    value: String,
    token: String,
    observed: Instant,
  ): BlockIncidentRecoveryAttempt =
    BlockIncidentRecoveryAttempt(
      value,
      token,
      BlockIncidentRecoveryAction.NONE,
      1,
      "idempotency-key",
      BlockIncidentRecoveryAttemptStatus.RESUMED,
      null,
      null,
      null,
      null,
      observed,
      observed.plusSeconds(60),
      "wf-1",
      null,
    )

  private val textFields =
    listOf(
      BlockIncidentReportKeys.SUMMARY,
      BlockIncidentReportKeys.WHAT_HAPPENED,
      BlockIncidentReportKeys.CAUSE,
      BlockIncidentReportKeys.REPRODUCTION_STEPS,
      BlockIncidentReportKeys.EVIDENCE,
      BlockIncidentReportKeys.RUNTIME_VERSION,
      BlockIncidentReportKeys.RUNTIME_BUILD,
      BlockIncidentReportKeys.OS_FAMILY,
      BlockIncidentReportKeys.PROVIDER_ID,
      BlockIncidentReportKeys.PHASE_STRATEGY_ID,
      BlockIncidentReportKeys.MODEL_ID,
      BlockIncidentReportKeys.FAILURE_CODE,
      BlockIncidentReportKeys.RECOVERY_ATTEMPT_ID,
      BlockIncidentReportKeys.RECOVERY_OUTCOMES,
    )
}
