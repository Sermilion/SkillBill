package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAuditAttempt
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.admitQaReaudit
import skillbill.workflow.taskruntime.model.audit.captureForTest
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.recordInteractionTrace
import skillbill.workflow.taskruntime.model.audit.interaction.requireInteractionTrace
import skillbill.workflow.taskruntime.model.audit.recordIndependently
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AcceptanceCriterionAuditAttemptsTest {
  @Test
  fun `read only discovery and targeted audits do not count as completed repairs`() {
    val state = state()
    val satisfied = state.copy(proseAudits = listOf(proseAudit("audit:pass", 1, true, "")))
    assertEquals(emptyList(), satisfied.auditHistory().attempts)

    val gapState =
      state.copy(
        criteria =
          listOf(
            criterion("AC-001", AcceptanceCriterionSourceDisposition.SOURCE_CLOSED),
            criterion("AC-002"),
          ),
        proseAudits = listOf(proseAudit("audit:gaps", 1, false, "Open criteria: AC-002")),
      )
    assertEquals(emptyList(), gapState.auditHistory().attempts)
  }

  @Test
  fun `repair presentation follows completed mutations and independent checks after discovery`() {
    val discovered = assertNotNull(state().recordProseAudit(proseAudit("discovery", 1, false, "Open criteria: AC-002")))
    val reserved = assertNotNull(discovered.reserve("repair-1"))
    assertEquals(emptyList(), reserved.auditHistory().attempts)
    val captured = assertNotNull(reserved.captureForTest("repair-1", "worker-output", "c".repeat(64), "checkpoint"))
    assertEquals(listOf(AcceptanceCriterionAuditAttempt("repair-1", 1, false, 1)), captured.auditHistory().attempts)
    val settled =
      assertNotNull(
        captured.recordIndependently(outcome("check-1", AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS)),
      )
    assertEquals(listOf(AcceptanceCriterionAuditAttempt("repair-1", 1, true, 0)), settled.auditHistory().attempts)
    assertEquals("AC-001", settled.criteria.first().canonicalRef)
    assertEquals(AcceptanceCriterionSourceDisposition.SOURCE_CLOSED, settled.criteria.first().sourceDisposition)
  }

  @Test
  fun `later successful repair cannot close an earlier failed history row`() {
    var current = assertNotNull(state().recordProseAudit(proseAudit("discovery", 1, false, "Open criteria: AC-002")))
    current =
      assertNotNull(
        assertNotNull(current.reserve("repair-1")).captureForTest(
          "repair-1",
          "first",
          "c".repeat(64),
          "checkpoint",
        ),
      )
    current =
      assertNotNull(
        current.recordIndependently(
          outcome(
            "check-1",
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
          ),
        ),
      )
    val first = current.auditHistory().attempts.single()
    current =
      assertNotNull(
        assertNotNull(current.reserve("repair-2")).captureForTest(
          "repair-2",
          "second",
          "d".repeat(64),
          "checkpoint-2",
        ),
      )
    current =
      assertNotNull(
        current.recordIndependently(
          outcome(
            "check-2",
            AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
          ).copy(reservationId = "repair-2"),
        ),
      )
    assertEquals(first, current.auditHistory().attempts.first())
    assertEquals(listOf(1, 0), current.auditHistory().attempts.map { it.uncoveredCount })
    assertEquals(listOf(AcceptanceCriterionRepairClosure("AC-002", "repair-2")), current.auditHistory().closures)
  }

  @Test
  fun `prose reclosure and interaction confirmation preserve repair attribution without adding attempts`() {
    val accepted = reclosedState()
    val expected = listOf(AcceptanceCriterionRepairClosure("AC-002", "repair-1"))
    assertEquals(expected, accepted.auditHistory().closures)
    val tracing =
      accepted.copy(
        interactionTrace =
          accepted.interactionTrace.copy(
            recheckRefs =
              listOf(
                "AC-002",
              ),
          ),
      ).requireInteractionTrace(emptyList())
    val confirmed =
      assertNotNull(
        tracing.recordInteractionTrace(
          AcceptanceCriterionInteractionTraceSettlement(
            "confirmation",
            "b".repeat(64),
            "d".repeat(64),
            "new-checkpoint",
            emptyList(),
            listOf(AcceptanceCriterionRecheckEntry("AC-002", AcceptanceCriterionRecheckVerdict.HOLDS, "src/Flow.kt:2")),
          ),
        ),
      )
    assertEquals(expected, confirmed.auditHistory().closures)
    assertEquals(1, confirmed.auditHistory().attempts.size)
    assertEquals(0, confirmed.auditHistory().attempts.single().uncoveredCount)
    assertEquals(confirmed, AcceptanceCriterionRepairState.restore(confirmed))
  }

  private fun reclosedState(): AcceptanceCriterionRepairState {
    val discovered = assertNotNull(state().recordProseAudit(proseAudit("discovery", 1, false, "Open criteria: AC-002")))
    val captured =
      assertNotNull(
        assertNotNull(discovered.reserve("repair-1")).captureForTest(
          "repair-1",
          "worker",
          "c".repeat(64),
          "checkpoint",
        ),
      )
    val closed =
      assertNotNull(
        captured.recordIndependently(
          outcome(
            "check-1",
            AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
          ),
        ),
      )
    val pending = assertNotNull(closed.admitQaReaudit(listOf("AC-002")))
    val reopened =
      assertNotNull(
        pending.recordProseAudit(
          proseAudit(
            "reopened",
            pending.revision + 1,
            false,
            "Gap",
          ).copy(criterionRef = "AC-002"),
        ),
      )
    assertEquals(emptyList(), reopened.auditHistory().closures)
    val rechecking = assertNotNull(reopened.reopenForOperatorAudit("source-recheck"))
    val accepted =
      assertNotNull(
        rechecking.recordProseAudit(
          proseAudit(
            "reclosed",
            rechecking.revision + 1,
            true,
            "Satisfied",
          ).copy(criterionRef = "AC-002", checkpointId = "checkpoint"),
        ),
      )
    return accepted
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
  ) = AcceptanceCriterionProseAudit(
    id,
    revision,
    null,
    satisfied,
    "b".repeat(64),
    prose,
    "c".repeat(64),
    "checkpoint:$id",
  )

  private fun outcome(
    id: String,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ) = AcceptanceCriterionRepairOutcome(
    outcomeId = id,
    reservationId = "repair-1",
    criterionRef = "AC-002",
    digest = "d".repeat(64),
    kind = kind,
    evidenceRef = "evidence:$id",
  )
}
