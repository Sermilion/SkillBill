package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AcceptanceCriterionAuditAttemptsTest {
  @Test
  fun `a satisfied initial audit closes the attempt and named gaps stay open`() {
    val state = state()
    val satisfied = state.copy(proseAudits = listOf(proseAudit("audit:pass", 1, true, "", emptyList())))
    assertEquals(listOf(AcceptanceCriterionAuditAttempt("audit:pass", 1, true, 0)), satisfied.auditAttempts())

    val gapState =
      state.copy(
        criteria =
          listOf(
            criterion("AC-001", AcceptanceCriterionSourceDisposition.SOURCE_CLOSED),
            criterion("AC-002"),
          ),
        proseAudits = listOf(proseAudit("audit:gaps", 1, false, "Open criteria: AC-002", listOf("AC-002"))),
      )
    assertEquals(listOf(AcceptanceCriterionAuditAttempt("audit:gaps", 1, false, 1)), gapState.auditAttempts())
  }

  @Test
  fun `targeted outcomes update gaps while repair outcomes do not become attempts`() {
    val state =
      state().copy(
        proseAudits = listOf(proseAudit("audit:initial", 1, false, "Open criteria: AC-001", listOf("AC-001"))),
        outcomes =
          listOf(
            outcome("repair:incomplete", 2, AcceptanceCriterionRepairOutcomeKind.INCOMPLETE),
            outcome("audit:failed", 3, AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE),
            outcome("repair:blocked", 4, AcceptanceCriterionRepairOutcomeKind.BLOCKED),
            outcome("audit:passed", 5, AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS),
          ),
      )

    assertEquals(
      listOf(
        AcceptanceCriterionAuditAttempt("audit:initial", 1, false, 1),
        AcceptanceCriterionAuditAttempt("audit:failed", 2, false, 1),
        AcceptanceCriterionAuditAttempt("audit:passed", 3, true, 0),
      ),
      state.auditAttempts(),
    )
  }

  @Test
  fun `historical audits without a retained discovery scope do not invent counts`() {
    val historical = state().copy(proseAudits = listOf(proseAudit("audit:legacy", 1, true, "All criteria met.")))

    val attempt = historical.auditAttempts().single()
    assertEquals(null, attempt.passed)
    assertEquals(null, attempt.uncoveredCount)
    assertNotNull(attempt.unavailableReason)
  }

  private fun state(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("Criterion AC-001", "Criterion AC-002"),
          listOf(criterion("AC-001"), criterion("AC-002")),
          emptyList(),
        ),
      ),
    )

  private fun criterion(
    ref: String,
    disposition: AcceptanceCriterionSourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
  ) = AcceptanceCriterionRepairCriterion(
    canonicalRef = ref,
    originalAlias = null,
    originalText = "Criterion $ref",
    ordinal = ref.last().digitToInt(),
    sourceDisposition = disposition,
    validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
    validationOwnerAvailable = true,
    evidenceRef = "evidence:$ref",
  )

  private fun proseAudit(
    id: String,
    revision: Long,
    satisfied: Boolean,
    prose: String,
    unresolvedRefs: List<String>? = null,
  ) = AcceptanceCriterionProseAudit(
    id,
    revision,
    null,
    satisfied,
    "b".repeat(64),
    prose,
    "c".repeat(64),
    "checkpoint:$id",
    unresolvedRefs?.let { AcceptanceCriterionDiscoverySnapshot(listOf("AC-001", "AC-002"), it) },
  )

  private fun outcome(
    id: String,
    revision: Int,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ) = AcceptanceCriterionRepairOutcome(
    outcomeId = id,
    reservationId = "reservation:$id",
    criterionRef = "AC-001",
    digest = "d".repeat(64),
    kind = kind,
    evidenceRef = "evidence:$id",
    verifiedRevision = revision.toLong(),
  )
}
