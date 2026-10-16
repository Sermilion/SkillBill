package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairStateTest {
  @Test
  fun `a named prose regression focuses verification but repeated unchanged rejection rechecks every criterion`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")).map {
              it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED)
            },
            emptyList(),
          ),
        ),
      )
    val rejection =
      AcceptanceCriterionProseAudit(
        "full:1",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "AC-001 is satisfied. AC-002 remains open. The second caller can bypass the reservation.",
        "c".repeat(64),
        "source:1",
      )
    val focused = assertNotNull(initial.recordProseAudit(rejection))
    assertEquals(listOf("AC-002"), focused.pendingAuditRefs)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, focused.stage)
    val rechecked =
      assertNotNull(
        focused.recordProseAudit(
          rejection.copy(
            id = "focused:1",
            revision = focused.revision + 1,
            criterionRef = "AC-002",
            satisfied = true,
            prose = "The second caller already enforces the reservation.",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED, rechecked.stage)
    val repeated =
      assertNotNull(
        rechecked.recordProseAudit(
          rejection.copy(
            id = "full:2",
            revision = rechecked.revision + 1,
          ),
        ),
      )
    assertEquals(listOf("AC-001", "AC-002"), repeated.pendingAuditRefs)
    val unknown = assertNotNull(initial.recordProseAudit(rejection.copy(prose = "AC-099 remains open.")))
    assertEquals(listOf("AC-001", "AC-002"), unknown.pendingAuditRefs)
  }

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
  fun `deferred unavailable prerequisites refuse admission while source closed prerequisites remain usable`() {
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
    for (disposition in AcceptanceCriterionSourceDisposition.entries) {
      val candidate =
        admission.copy(
          criteria =
            listOf(
              prerequisite.copy(sourceDisposition = disposition),
              admission.criteria[1],
            ),
        )
      val admitted = AcceptanceCriterionRepairState.admit(candidate)
      if (disposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
        assertEquals(listOf("AC-002"), assertNotNull(admitted).unresolvedQueue)
      } else {
        assertNull(admitted)
      }
    }
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
    assertEquals(listOf("AC-002", "AC-001"), reopened.unresolvedQueue)
    assertEquals(reopened, AcceptanceCriterionRepairState.restore(reopened))
    assertEquals(reopened, reopened.recordFinalFullAudit(input))
    assertNull(reopened.recordFinalFullAudit(input.copy(checkpointId = "other-checkpoint")))
    assertNull(reopened.recordFinalFullAudit(input.copy(regressedRefs = emptySet())))
    assertNull(reopened.recordFinalFullAudit(input.copy(dependencies = emptyList())))
    assertNull(reopened.recordFinalFullAudit(input.copy(auditBindingDigest = "d".repeat(64))))
    assertNull(discovered.recordFinalFullAudit(input.copy(evaluatedRefs = setOf("AC-001"))))
  }

  @Test
  fun `initial discovery stays restorable across clarification and progress until the first audit lands`() {
    val discovered =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")),
            emptyList(),
          ),
        ),
      )
    val clarified = assertNotNull(discovered.retainClarification("Report a verdict for the full audit."))
    val progressed = clarified.retainProseProgress()

    assertEquals(AcceptanceCriterionRepairMode.INITIAL_DISCOVERY, progressed.mode)
    assertEquals(2L, progressed.revision)
    assertEquals(clarified, AcceptanceCriterionRepairState.restore(clarified))
    assertEquals(progressed, AcceptanceCriterionRepairState.restore(progressed))

    val audited =
      assertNotNull(
        progressed.clearPendingClarification().recordProseAudit(
          AcceptanceCriterionProseAudit(
            "audit:3",
            3,
            null,
            false,
            "b".repeat(64),
            "AC-002 is open.",
            "c".repeat(64),
            "checkpoint:3",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, audited.stage)
    assertEquals(audited, AcceptanceCriterionRepairState.restore(audited))
    assertNull(
      AcceptanceCriterionRepairState.restore(
        audited.copy(
          mode = AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
          stage = AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED,
          pendingAuditRefs = emptyList(),
        ),
      ),
    )
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
