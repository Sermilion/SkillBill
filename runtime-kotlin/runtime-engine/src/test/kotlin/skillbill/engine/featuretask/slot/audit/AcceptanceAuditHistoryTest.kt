package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcceptanceAuditHistoryTest {
  @Test
  fun `audit repair counters ignore read only ordinals and launch reservations`() {
    val criteria =
      (1..2).map { ordinal ->
        AcceptanceCriterionRepairCriterion(
          "AC-00$ordinal",
          null,
          "criterion $ordinal",
          ordinal,
          AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
          AcceptanceCriterionValidationObligation.DEFERRED,
          true,
          "gap:$ordinal",
        )
      }
    var state =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            criteria.map { it.originalText },
            criteria,
            emptyList(),
          ),
        ),
      )

    fun assertDisplayedAttempt(expected: Int) {
      listOf("audit", "audit_plan_fix", "audit_implement_fix").forEach { phase ->
        val execution =
          auditCurrentExecution(
            phase,
            FeatureTaskRuntimeCurrentPhaseExecutionContext(
              phase,
              emptyMap(),
              listOf(FeatureTaskRuntimePhaseStatus(phase, "running", 7, "claude", false)),
              emptyList(),
              null,
              state,
            ),
          )
        if (expected == 0) {
          assertNull(execution)
        } else {
          assertNotNull(execution)
          assertEquals(IdeStatusCurrentPhaseExecutionKind.ATTEMPT, execution.kind)
          assertEquals(expected, execution.count)
        }
      }
    }

    val discovery = state
    val reserved = assertNotNull(state.reserve("reserved"))
    state = reserved
    assertDisplayedAttempt(0)
    val mutation =
      AcceptanceCriterionCompletedMutation(
        state.catalogId,
        state.specDigest,
        state.executionId,
        "AC-001",
        "reserved",
        "worker",
        "output",
        "b".repeat(64),
        "a".repeat(64),
        "before",
        "c".repeat(64),
        "after",
        emptyList(),
        AcceptanceCriterionMutationDisposition.INCOMPLETE,
      )
    state = assertNotNull(state.retainCompletedMutation(mutation))
    assertDisplayedAttempt(1)
    state =
      assertNotNull(
        discovery.recordProseAudit(
          AcceptanceCriterionProseAudit(
            "discovery",
            1,
            null,
            false,
            "b".repeat(64),
            "Open criteria: AC-002",
            "c".repeat(64),
            "checkpoint",
          ),
        ),
      )
    assertEquals("AC-002", state.currentCriterionRef)
    assertDisplayedAttempt(0)
  }
}
