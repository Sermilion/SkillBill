package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PhaseHistoricalInterpreterTest {
  @Test
  fun `audit repair counters follow the current criterion across retries and advancement`() {
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
          assertNotNull(
            PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1).currentExecution(
              FeatureTaskRuntimeCurrentPhaseExecutionContext(
                phase,
                emptyMap(),
                listOf(FeatureTaskRuntimePhaseStatus(phase, "running", 7, "claude", false)),
                emptyList(),
                null,
                state,
              ),
            ),
          )
        assertEquals(IdeStatusCurrentPhaseExecutionKind.ATTEMPT, execution.kind)
        assertEquals(expected, execution.count)
        assertEquals(state.currentCriterionRef, execution.criterionRef)
      }
    }

    repeat(3) { index ->
      val reservation = "repair:$index"
      state = assertNotNull(state.reserve(reservation))
      assertDisplayedAttempt(index + 1)
      state =
        assertNotNull(
          state.completeRepairAwaitingTargetedVerification(reservation, "repaired", "b".repeat(64), "checkpoint"),
        )
      assertDisplayedAttempt(index + 1)
      state =
        assertNotNull(
          state.record(
            AcceptanceCriterionRepairOutcome(
              "outcome:$index",
              reservation,
              "AC-001",
              "c".repeat(64),
              if (index == 2) {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
              } else {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
              },
              "verification:$index",
            ),
          ),
        )
    }

    assertEquals("AC-002", state.currentCriterionRef)
    assertEquals(2, state.unsuccessfulTotals["AC-001"])
    assertDisplayedAttempt(1)
  }
}
