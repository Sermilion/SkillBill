package skillbill.workflow.taskruntime.model.blockincident.report

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelation
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
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductReportSanitizerTest {
  @Test
  fun redactsSecretsAndDropsWorktreePaths() {
    val occurrence =
      BlockIncidentOccurrence(
        occurrenceToken = "bi_${"a".repeat(64)}",
        scope = BlockIncidentScope(workflowId = "wf-1", phaseId = "implement"),
        failure =
          BlockIncidentFailure(
            origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
            reason = "blocked",
            reasonRetention = BlockIncidentReasonRetention.COMPLETE,
            failureCode = "runner_failed",
            exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
          ),
        observedAt = Instant.parse("2026-10-21T12:00:00Z"),
        evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
        identityAvailability = BlockIncidentIdentityAvailability.AVAILABLE,
        fieldAvailability = emptyMap(),
        correlation =
          BlockIncidentCorrelation(
            worktreePath = "/home/alice/private/project",
          ),
      )
    val diagnosis =
      BlockIncidentDiagnosisRecord(
        occurrence.occurrenceToken,
        3,
        "Credential leaked ghp_${"x".repeat(36)} for alice@example.com at /home/alice/private/project",
        "Failure returned.",
        null,
        BlockIncidentCertainty.DIAGNOSED,
        null,
        BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT,
        emptyList(),
        emptyList(),
        BlockIncidentRecoveryAction.NONE,
        null,
        false,
        emptySet(),
        BlockIncidentInvestigatorKind.DETERMINISTIC,
        Instant.parse("2026-10-21T12:01:00Z"),
      )

    val draft =
      assertIs<ProductReportDraft>(
        ProductReportSanitizer.sanitize(
          occurrence,
          diagnosis,
          emptyList(),
          ProductReportRuntimeFacts("1.0", "build-1", "Linux", null, null, null),
        ),
      )

    assertFalse(draft.body.contains("ghp_"))
    assertFalse(draft.body.contains("alice@example.com"))
    assertFalse(draft.body.contains("/home/alice"))
    assertFalse(draft.body.contains("private/project"))
    assertTrue(draft.body.endsWith("<!-- skill-bill-report-fingerprint:${draft.fingerprint} -->"))
    assertTrue(draft.body.toByteArray(Charsets.UTF_8).size <= ProductReportSanitizer.MAX_BODY_BYTES)
    assertTrue(draft.fieldOutcomes["summary"] == ProductReportFieldOutcome.REDACTED)
    assertTrue(draft.fieldOutcomes["worktree_path"] == ProductReportFieldOutcome.DROPPED)
  }
}
