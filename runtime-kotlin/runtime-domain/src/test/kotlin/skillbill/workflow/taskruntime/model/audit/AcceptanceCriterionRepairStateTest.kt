package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairStateTest {
  @Test
  fun `admission keeps composite criterion identity and orders prerequisites before ordinal ties`() {
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
    assertEquals(listOf("AC-001", "AC-003", "AC-002"), state.unresolvedQueue)
    assertEquals("S1-002. First part. Second part.", state.criteria[1].originalText)
    assertNull(
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
    val failed = reserved.record(outcome("outcome-1", "reservation-1", "AC-001", successful = false))!!
    val reservedAgain = failed.reserve("reservation-2")!!
    val success = outcome("outcome-2", "reservation-2", "AC-001", successful = true)
    val closed = reservedAgain.record(success)!!

    assertEquals(1, closed.unsuccessfulTotals["AC-001"])
    assertEquals("AC-002", closed.currentCriterionRef)
    assertEquals(closed, closed.record(success))
    assertNull(closed.record(success.copy(digest = "b".repeat(64))))
  }

  private fun criterion(ref: String, ordinal: Int, text: String) =
    AcceptanceCriterionRepairCriterion(
      canonicalRef = ref,
      originalAlias = originalAcceptanceCriterionAlias(text),
      originalText = text,
      ordinal = ordinal,
      sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
      validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
      validationOwnerAvailable = true,
      evidenceRef = "audit:$ref",
    )

  private fun dependency(dependent: String, prerequisite: String) =
    AcceptanceCriterionRepairDependency(
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
  ) =
    AcceptanceCriterionRepairOutcome(
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
