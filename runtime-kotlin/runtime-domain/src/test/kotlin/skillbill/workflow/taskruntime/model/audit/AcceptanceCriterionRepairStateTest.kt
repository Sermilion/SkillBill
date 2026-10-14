package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairStateTest {
  @Test
  fun `admission keeps composite identity and follows authored order despite declared dependencies`() {
    val state =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first", "S1-002. First part. Second part.", "third"),
          criteria =
            listOf(
              criterion("AC-002", 2, "S1-002. First part. Second part."),
              criterion("AC-003", 3, "third"),
              criterion("AC-001", 1, "first"),
            ),
          dependencies = listOf(dependency("AC-002", "AC-003")),
        ),
      )

    assertNotNull(state)
    assertEquals(listOf("AC-001", "AC-002", "AC-003"), state.unresolvedQueue)
    assertEquals("S1-002. First part. Second part.", state.criteria[1].originalText)
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first"),
          criteria = listOf(criterion("AC-001", 1, "first")),
          dependencies = listOf(dependency("AC-001", "AC-001")),
        ),
      ),
    )
  }

  @Test
  fun `success and identical replay preserve unsuccessful budget and do not advance twice`() {
    val initial =
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog-1",
          executionId = "execution-1",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first", "second"),
          criteria = listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")),
          dependencies = emptyList(),
        ),
      )!!
    val reserved = initial.reserve("reservation-1")!!
    val verified =
      reserved.completeRepairAwaitingTargetedVerification(
        "reservation-1",
        "repair:1",
        "d".repeat(64),
        "checkpoint:1",
      )!!
    val failed = verified.record(outcome("outcome-1", "reservation-1", "AC-001", successful = false))!!
    val reservedAgain = failed.reserve("reservation-2")!!
    val success = outcome("outcome-2", "reservation-2", "AC-001", successful = true)
    val repaired =
      reservedAgain.completeRepairAwaitingTargetedVerification(
        "reservation-2",
        "repair:2",
        "d".repeat(64),
        "checkpoint:2",
      )!!
    val closed = repaired.record(success)!!

    assertEquals(1, closed.unsuccessfulTotals["AC-001"])
    assertEquals("AC-002", closed.currentCriterionRef)
    assertEquals(closed, closed.record(success))
    assertNull(closed.record(success.copy(digest = "a".repeat(64))))
  }

  @Test
  fun `admission does not require dependency or validation-owner evidence`() {
    val prerequisite =
      criterion("AC-001", 1, "first").copy(
        validationObligation = AcceptanceCriterionValidationObligation.DEFERRED,
        validationOwnerAvailable = false,
      )
    val admission =
      AcceptanceCriterionRepairAdmission(
        "catalog",
        "execution",
        "a".repeat(64),
        listOf("first", "second"),
        listOf(prerequisite, criterion("AC-002", 2, "second")),
        listOf(dependency("AC-002", "AC-001")),
      )
    val admitted = assertNotNull(AcceptanceCriterionRepairState.admit(admission))
    assertEquals("AC-001", admitted.currentCriterionRef)
  }

  @Test
  fun `empty discovery requires final proof and refuses conflicting final digest reuse after reopening`() {
    val admission =
      AcceptanceCriterionRepairAdmission(
        "catalog",
        "execution",
        "a".repeat(64),
        listOf("first", "second"),
        listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")).map {
          it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
        },
        emptyList(),
      )
    val discovered = assertNotNull(AcceptanceCriterionRepairState.admit(admission))
    assertEquals(AcceptanceCriterionRepairMode.FINAL_FULL, discovered.mode)
    assertEquals(discovered, AcceptanceCriterionRepairState.restore(discovered))
    val input =
      AcceptanceCriterionRepairFinalAuditInput(
        setOf("AC-001", "AC-002"),
        setOf("AC-001", "AC-002"),
        "b".repeat(64),
        "c".repeat(64),
        "checkpoint:final",
        dependencies = listOf(dependency("AC-001", "AC-002")),
      )
    val reopened = assertNotNull(discovered.recordFinalFullAudit(input))
    assertEquals(listOf("AC-001", "AC-002"), reopened.unresolvedQueue)
    assertEquals(reopened, AcceptanceCriterionRepairState.restore(reopened))
    assertEquals(reopened, reopened.recordFinalFullAudit(input))
    assertNull(reopened.recordFinalFullAudit(input.copy(checkpointId = "other-checkpoint")))
    assertNull(reopened.recordFinalFullAudit(input.copy(regressedRefs = emptySet())))
    assertNull(reopened.recordFinalFullAudit(input.copy(dependencies = emptyList())))
    assertNull(reopened.recordFinalFullAudit(input.copy(auditBindingDigest = "d".repeat(64))))
    assertNull(discovered.recordFinalFullAudit(input.copy(evaluatedRefs = setOf("AC-001"))))
  }

  private fun criterion(
    ref: String,
    ordinal: Int,
    text: String,
  ) = AcceptanceCriterionRepairCriterion(
    canonicalRef = ref,
    originalAlias = originalAcceptanceCriterionAlias(text),
    originalText = text,
    ordinal = ordinal,
    sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
    validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
    validationOwnerAvailable = true,
    evidenceRef = "audit:$ref",
  )

  private fun dependency(
    dependent: String,
    prerequisite: String,
  ) = AcceptanceCriterionRepairDependency(
    dependentRef = dependent,
    prerequisiteRef = prerequisite,
    reason = "declared by audit output",
    declaringOutputRef = "output:1",
    sourceEvidenceRef = "source:1",
  )

  private fun outcome(
    id: String,
    reservationId: String,
    criterionRef: String,
    successful: Boolean,
  ) = AcceptanceCriterionRepairOutcome(
    outcomeId = id,
    reservationId = reservationId,
    criterionRef = criterionRef,
    digest = if (successful) "b".repeat(64) else "c".repeat(64),
    kind =
      if (successful) {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
      } else {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
      },
    evidenceRef = "evidence:$id",
  )
}
