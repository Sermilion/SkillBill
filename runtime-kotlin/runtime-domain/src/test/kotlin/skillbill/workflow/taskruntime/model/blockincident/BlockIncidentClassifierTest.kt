package skillbill.workflow.taskruntime.model.blockincident

import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockIncidentClassifierTest {
  @Test
  fun `invalid output with retained rejected output is a suspected skill-bill defect`() {
    val record =
      BlockIncidentClassifier.classify(
        occurrence(failureCode = null, disposition = "invalid_output"),
        listOf(evidence(BlockIncidentDiagnosisEvidenceKind.REJECTED_OUTPUT)),
        emptySet(),
        NOW,
      )

    assertEquals(BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT, record.responsibleCategory)
    assertEquals(BlockIncidentCertainty.SUSPECTED, record.certainty)
    assertTrue(record.validation().isEmpty())
  }

  @Test
  fun `unmapped failure stays unknown with no workaround and never echoes the raw reason`() {
    val record =
      BlockIncidentClassifier.classify(
        occurrence(failureCode = "SomethingNew.CODE", disposition = null, reason = "secret token abc123"),
        listOf(evidence(BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE)),
        emptySet(),
        NOW,
      )

    assertEquals(BlockIncidentResponsibleCategory.UNRESOLVED, record.responsibleCategory)
    assertEquals(BlockIncidentCertainty.UNKNOWN, record.certainty)
    assertEquals(BlockIncidentRecoveryAction.NONE, record.proposedRecoveryAction)
    assertNull(record.recoveryEffects)
    assertTrue(record.noWorkaroundKnown)
    assertTrue(record.uncertaintyNote.orEmpty().contains("SomethingNew.CODE"))
    assertFalse(record.summary.contains("abc123") || record.whatHappened.contains("abc123"))
    assertTrue(record.validation().isEmpty())
  }

  @Test
  fun `needs user action on a configuration code is diagnosed and reopens the blocked phase`() {
    val configuration = RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION
    val record =
      BlockIncidentClassifier.classify(
        occurrence("${configuration.declaringJavaClass.simpleName}.${configuration.name}", "needs_user_action"),
        listOf(evidence(BlockIncidentDiagnosisEvidenceKind.PHASE_LEDGER)),
        emptySet(),
        NOW,
      )

    assertEquals(BlockIncidentResponsibleCategory.USER_ENVIRONMENT, record.responsibleCategory)
    assertEquals(BlockIncidentCertainty.DIAGNOSED, record.certainty)
    assertEquals(
      listOf(
        BlockIncidentPrerequisite(
          BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED,
          BlockIncidentPrerequisiteCheck.USER_CONFIRMED,
        ),
      ),
      record.prerequisites,
    )
    assertEquals(BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE, record.proposedRecoveryAction)
    assertTrue(record.validation().isEmpty())
  }

  @Test
  fun `validation rejects diagnosed without supporting evidence and unresolved that claims certainty`() {
    val diagnosed =
      BlockIncidentClassifier.classify(
        occurrence(failureCode = null, disposition = "needs_user_action"),
        listOf(evidence(BlockIncidentDiagnosisEvidenceKind.ORIGINAL_FAILURE)),
        emptySet(),
        NOW,
      ).copy(
        responsibleCategory = BlockIncidentResponsibleCategory.USER_ENVIRONMENT,
        certainty = BlockIncidentCertainty.DIAGNOSED,
      )
    val unresolved =
      diagnosed.copy(
        responsibleCategory = BlockIncidentResponsibleCategory.UNRESOLVED,
        certainty = BlockIncidentCertainty.SUSPECTED,
      )

    assertEquals(
      listOf(BlockIncidentDiagnosisViolationCode.DIAGNOSED_WITHOUT_EVIDENCE),
      diagnosed.validation().map { it.code },
    )
    assertEquals(
      listOf(BlockIncidentDiagnosisViolationCode.UNRESOLVED_NOT_UNKNOWN),
      unresolved.validation().map { it.code },
    )
  }

  private fun evidence(kind: BlockIncidentDiagnosisEvidenceKind) =
    BlockIncidentDiagnosisEvidence(kind, "${kind.wireValue}:ref", "summary", "a".repeat(64), trusted = true)

  private fun occurrence(
    failureCode: String?,
    disposition: String?,
    reason: String = "phase blocked",
  ) = BlockIncidentOccurrence(
    occurrenceToken = "bi_${"d".repeat(64)}",
    scope = BlockIncidentScope(workflowId = "workflow", phaseId = "implement", attempt = 2, generation = 1),
    failure =
      BlockIncidentFailure(
        origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
        reason = reason,
        reasonRetention = BlockIncidentReasonRetention.COMPLETE,
        failureCode = failureCode,
        failureDisposition = disposition,
        exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      ),
    observedAt = NOW,
    evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
    identityAvailability = BlockIncidentIdentityAvailability.PARTIAL,
    fieldAvailability = emptyMap(),
  )

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-22T12:00:00Z")
  }
}
