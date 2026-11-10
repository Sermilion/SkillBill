package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_FIRST_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairVersionUpgradeTest {
  @Test
  fun `0_2 in-flight stages restart discovery while retaining closed criteria and outcome history`() {
    val reserved = closeFirst(admittedState()).reserve("reservation")!!
    val awaiting =
      reserved.completeRepairAwaitingTargetedVerification(
        "reservation",
        "repair:evidence",
        "b".repeat(64),
        "checkpoint",
      )!!
    val blocked = budgetBlockedState()

    listOf(reserved, awaiting, blocked).forEach { prior ->
      val old = oldWire(prior, ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION)
      val upgraded =
        FeatureTaskRuntimeWorkflowArtifactMap.from(old)
          .upgradeRepairStateFromPrevious()
      val restored = assertNotNull(upgraded).toAcceptanceCriterionRepairState("upgraded")
      assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, restored.stage)
      assertEquals(null, restored.reservation)
      assertEquals(emptyMap(), restored.chargedAttempts)
      assertEquals(prior.criteria.map { it.copy(escalated = false) }, restored.criteria)
      assertEquals(prior.outcomes.size, restored.outcomes.size)
      assertEquals(prior.outcomes.map { it.outcomeId }, restored.outcomes.map { it.outcomeId })
      assertEquals(prior.sourceTransitions, restored.sourceTransitions)
      assertEquals(restored, AcceptanceCriterionRepairState.restore(restored))
    }
  }

  @Test
  fun `0_1 records pass through both migration steps`() {
    val original =
      oldWire(admittedState(), ACCEPTANCE_CRITERION_REPAIR_STATE_FIRST_CONTRACT_VERSION)
        .toMutableMap()
        .also { it.remove(Keys.INTERACTION_TRACE) }
    val restored =
      FeatureTaskRuntimeWorkflowArtifactMap.from(original)
        .upgradeRepairStateFromPrevious()!!
        .toAcceptanceCriterionRepairState("upgraded-0.1")

    assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, restored.stage)
    assertEquals(listOf(emptyList(), emptyList()), restored.criteria.map { it.gaps })
    assertEquals(emptyMap(), restored.chargedAttempts)
  }

  private fun oldWire(
    state: AcceptanceCriterionRepairState,
    version: String,
  ): Map<String, Any?> {
    val wire = state.asRepairWireMap().toMutableMap()
    wire[Keys.CONTRACT_VERSION] = version
    wire[Keys.CRITERIA] =
      assertIs<List<*>>(wire[Keys.CRITERIA]).map { raw ->
        assertIs<Map<*, *>>(raw).filterKeys { it !in setOf(Keys.GAPS, Keys.PLAN_GENERATION, Keys.ESCALATED) }
      }
    wire[Keys.OUTCOMES] =
      assertIs<List<*>>(wire[Keys.OUTCOMES]).map { raw ->
        assertIs<Map<*, *>>(raw).filterKeys { it !in setOf(Keys.CHARGED, Keys.OPEN_GAPS_BEFORE, Keys.OPEN_GAPS_AFTER) }
      }
    wire[Keys.UNSUCCESSFUL_TOTALS] = state.chargedAttempts
    wire[Keys.BUDGET_START_OUTCOME_INDEX] = 0
    wire.remove(Keys.CHARGED_ATTEMPTS)
    wire.remove(Keys.LATE_FINDINGS)
    wire.remove(Keys.REPAIR_CHANGED_PATHS)
    return wire
  }

  private fun admittedState(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first", "second"),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "first",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.REQUIRED,
              true,
              "audit:1",
            ),
            AcceptanceCriterionRepairCriterion(
              "AC-002",
              null,
              "second",
              2,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.REQUIRED,
              true,
              "audit:2",
            ),
          ),
          emptyList(),
        ),
      ),
    )

  private fun budgetBlockedState(): AcceptanceCriterionRepairState {
    var state = closeFirst(admittedState())
    repeat(3) { attempt ->
      val reservationId = "reservation-$attempt"
      val awaiting =
        state.reserve(reservationId)!!
          .completeRepairAwaitingTargetedVerification(
            reservationId,
            "repair:$attempt",
            "b".repeat(64),
            "checkpoint:$attempt",
          )!!
      state =
        awaiting.record(
          AcceptanceCriterionRepairOutcome(
            "outcome:$attempt",
            reservationId,
            "AC-002",
            "c".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            "evidence:$attempt",
          ),
        )!!
    }
    return state
  }

  private fun closeFirst(initial: AcceptanceCriterionRepairState): AcceptanceCriterionRepairState {
    val reservationId = "first-reservation"
    val awaiting =
      initial.reserve(reservationId)!!
        .completeRepairAwaitingTargetedVerification(
          reservationId,
          "repair:first",
          "b".repeat(64),
          "checkpoint:first",
        )!!
    return awaiting.record(
      AcceptanceCriterionRepairOutcome(
        "first-success",
        reservationId,
        "AC-001",
        "c".repeat(64),
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
        "evidence:first",
      ),
    )!!
  }
}
