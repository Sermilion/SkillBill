package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceCriterionRepairStateTest {
  @Test
  fun `after discovery each criterion settles alone and an empty queue is accepted without a full re-audit`() {
    val initial =
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
    val discovery =
      AcceptanceCriterionProseAudit(
        "full:1",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "AC-002 remains open: the second caller can bypass the reservation.",
        "c".repeat(64),
        "source:1",
      )
    val queued = assertNotNull(initial.recordProseAudit(discovery))
    assertEquals(listOf("AC-001", "AC-002"), queued.pendingAuditRefs)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, queued.stage)

    fun AcceptanceCriterionRepairState.focusedAccept(ref: String) =
      assertNotNull(
        recordProseAudit(
          discovery.copy(id = "focused:$ref", revision = revision + 1, criterionRef = ref, satisfied = true),
        ),
      )

    val first = queued.focusedAccept("AC-001")
    assertEquals(listOf("AC-002"), first.pendingAuditRefs)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, first.stage)
    val accepted = first.focusedAccept("AC-002")
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage)
    assertEquals(AcceptanceCriterionRepairMode.ACCEPTED, accepted.mode)
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
    assertNull(
      accepted.recordProseAudit(discovery.copy(id = "full:2", revision = accepted.revision + 1)),
      "an accepted queue trusts its settled criteria and admits no later full re-check",
    )
  }

  @Test
  fun `discovery accepts unnamed criteria and each named gap is repaired and verified once before moving on`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second", "third"),
            listOf(
              criterion("AC-001", 1, "first"),
              criterion("AC-002", 2, "second"),
              criterion("AC-003", 3, "third"),
            ),
            emptyList(),
          ),
        ),
      )
    val discovery =
      AcceptanceCriterionProseAudit(
        "full:1",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "AC-001 and AC-003 are met. AC-002 lets a second caller bypass the reservation.\nOpen criteria: AC-002",
        "c".repeat(64),
        "source:1",
      )

    val queued = assertNotNull(initial.recordProseAudit(discovery))

    assertEquals(listOf("AC-002"), queued.unresolvedQueue)
    assertEquals("AC-002", queued.currentCriterionRef)
    assertEquals(emptyList(), queued.pendingAuditRefs, "no focused audit precedes the repair")
    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, queued.stage)
    assertEquals(
      listOf(AcceptanceCriterionSourceDisposition.SOURCE_CLOSED, AcceptanceCriterionSourceDisposition.SOURCE_CLOSED),
      queued.criteria.filter { it.canonicalRef != "AC-002" }.map { it.sourceDisposition },
    )
    assertEquals(queued, AcceptanceCriterionRepairState.restore(queued))

    val repaired =
      assertNotNull(
        assertNotNull(queued.reserve("reservation-1"))
          .completeRepairAwaitingTargetedVerification("reservation-1", "repair:1", "d".repeat(64), "checkpoint:1"),
      )
    val accepted = assertNotNull(repaired.record(outcome("outcome-1", "reservation-1", "AC-002", successful = true)))

    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage, "verification is the only post-repair audit")
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
  }

  @Test
  fun `a discovery without a readable open criteria line falls back to auditing every criterion`() {
    assertNull(openCriteriaNamedIn("AC-002 remains open."))
    assertEquals(emptySet(), openCriteriaNamedIn("Open criteria: none"))
    assertEquals(setOf("AC-002", "AC-005"), openCriteriaNamedIn("Gaps below.\n**Open criteria:** ac-002, AC-005"))
  }

  @Test
  fun `an initial discovery that finds every criterion met is accepted at once`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(criterion("AC-001", 1, "first")),
            emptyList(),
          ),
        ),
      )
    val accepted =
      assertNotNull(
        initial.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "full:1",
            initial.revision + 1,
            null,
            true,
            "b".repeat(64),
            "Every production criterion is met.",
            "c".repeat(64),
            "source:1",
          ),
        ),
      )
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, accepted.stage)
    assertEquals(emptyList(), accepted.pendingAuditRefs)
    assertEquals(accepted, AcceptanceCriterionRepairState.restore(accepted))
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
  fun `a discovery with nothing unresolved is accepted and restorable`() {
    val discovered =
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
    assertEquals(AcceptanceCriterionRepairMode.ACCEPTED, discovered.mode)
    assertEquals(AcceptanceCriterionRepairStage.ACCEPTED, discovered.stage)
    assertEquals(discovered, AcceptanceCriterionRepairState.restore(discovered))
  }

  @Test
  fun `a QA re-audit reopens only the named accepted criteria and refuses foreign or empty scopes`() {
    val accepted =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second", "third"),
            listOf(
              criterion("AC-001", 1, "first"),
              criterion("AC-002", 2, "second"),
              criterion("AC-003", 3, "third"),
            ).map { it.copy(sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) },
            emptyList(),
          ),
        ),
      )
    assertEquals(emptyList(), accepted.qaReauditRefs(emptySet()))
    assertNull(accepted.qaReauditRefs(setOf("AC-009")))
    assertNull(accepted.admitQaReaudit(emptyList()))
    assertNull(accepted.admitQaReaudit(listOf("AC-003", "AC-001")))

    val refs = assertNotNull(accepted.qaReauditRefs(setOf("AC-003", "AC-001")))
    val reaudit = assertNotNull(accepted.admitQaReaudit(refs))

    assertEquals(listOf("AC-001", "AC-003"), reaudit.pendingAuditRefs)
    assertEquals("AC-001", reaudit.currentCriterionRef)
    assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, reaudit.stage)
    assertEquals(accepted.criteria, reaudit.criteria)
    assertEquals(reaudit, AcceptanceCriterionRepairState.restore(reaudit))
    assertNull(reaudit.qaReauditRefs(setOf("AC-002")))
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

  @Test
  fun `repeated progress advances its revision without clearing failures and only the same completion replays`() {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first"),
            listOf(criterion("AC-001", 1, "first")),
            emptyList(),
          ),
        ),
      )
    val first = assertNotNull(initial.reserve("failed-cycle"))
    val failed =
      assertNotNull(
        first.record(
          outcome(
            "failure",
            "failed-cycle",
            "AC-001",
            false,
          ).copy(kind = AcceptanceCriterionRepairOutcomeKind.INCOMPLETE),
        ),
      )
    val reserved = assertNotNull(failed.reserve("continued-cycle"))
    val continued =
      assertNotNull(reserved.continueReservedRepair("continued-cycle", "Work remains.", reserved.revision))
    assertEquals(reserved.reservation, continued.reservation)
    assertEquals(failed.unsuccessfulTotals, continued.unsuccessfulTotals)
    assertEquals(failed.outcomes, continued.outcomes)
    assertEquals(reserved.revision + 1, continued.revision)
    assertEquals(continued, continued.continueReservedRepair("continued-cycle", "Work remains.", reserved.revision))
    val next = assertNotNull(continued.continueReservedRepair("continued-cycle", "Work remains.", continued.revision))
    assertEquals(continued.revision + 1, next.revision)
    assertNull(next.continueReservedRepair("continued-cycle", "Work remains.", reserved.revision))
    assertEquals(next, AcceptanceCriterionRepairState.restore(next))
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
