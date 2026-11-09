package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
import skillbill.workflow.taskruntime.artifact.historical.asEngineProgressWireMap
import skillbill.workflow.taskruntime.artifact.historical.asEngineRepairWireMap
import skillbill.workflow.taskruntime.artifact.historical.toEngineImplementationProgress
import skillbill.workflow.taskruntime.artifact.historical.toEngineRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAccountingPolicy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as ImplKeys

class AcceptanceCriterionPreAdoptionTest {
  @Test
  fun `supported worker completion and restart keep the supported format without manufacturing evidence`() {
    val reserved = assertNotNull(FeatureTaskRuntimeImplementationProgress.preAdoption(listOf("AC-001")).reserveWorker())
    val wire = reserved.asEngineProgressWireMap()
    assertEquals(
      FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION,
      wire[ImplKeys.CONTRACT_VERSION],
    )
    val restarted = wire.toEngineImplementationProgress("supported-worker")
    assertEquals(reserved, restarted)
    assertTrue(assertNotNull(restarted.closeCurrent()).complete)
    assertTrue(restarted.completedMutations.isEmpty())
    assertTrue(restarted.independentChecks.isEmpty())

    val strict = assertNotNull(FeatureTaskRuntimeImplementationProgress.initial(listOf("AC-001")).reserveWorker())
    val target = strict.asImplementationProgressWireMap()
    val restored = target.toEngineImplementationProgress("target-worker")
    assertEquals(AcceptanceCriterionAccountingPolicy.VERIFIED_EVIDENCE, restored.accountingPolicy)
    assertNull(restored.closeCurrent())
    assertEquals(target, restored.asEngineProgressWireMap())
  }

  @Test
  fun `supported repair budgets survive restart and retry without rewriting outcome history as target evidence`() {
    var state = assertNotNull(AcceptanceCriterionRepairState.admitPreAdoption(admission()))
    for (index in 1..3) {
      state = assertNotNull(state.reserve("repair-$index"))
      state =
        assertNotNull(
          state.completeRepairAwaitingTargetedVerification("repair-$index", "output-$index", DIGEST, "tree-$index"),
        )
      assertEquals(AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION, state.stage)
      state =
        assertNotNull(
          state.record(
            AcceptanceCriterionRepairOutcome(
              "check-$index",
              "repair-$index",
              "AC-001",
              DIGEST,
              AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
              "gap-$index",
            ),
          ),
        )
      val wire = state.asEngineRepairWireMap()
      assertEquals(ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION, wire[Keys.CONTRACT_VERSION])
      state = wire.toEngineRepairState("supported-repair")
    }
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
    assertEquals(3, state.unsuccessfulTotals["AC-001"])
    assertNull(state.reserve("repair-4"))
    val resumed = assertNotNull(state.reopenForOperatorAudit())
    assertEquals(state.outcomes, resumed.outcomes)
    assertEquals(state.sourceTransitions, resumed.sourceTransitions)
    assertEquals(3, resumed.budgetStartOutcomeIndex)
    assertTrue(resumed.unsuccessfulTotals.isEmpty())
    assertTrue(resumed.completedMutations.isEmpty())
    assertTrue(resumed.independentChecks.isEmpty())
    assertTrue(resumed.repairBudgets.isEmpty())
    assertEquals(resumed, resumed.asEngineRepairWireMap().toEngineRepairState("resumed"))
  }

  @Test
  fun `target repair records cannot fall back to pre-adoption completion when terminal capture is missing`() {
    val strict = assertNotNull(assertNotNull(AcceptanceCriterionRepairState.admit(admission())).reserve("repair-1"))
    val target = strict.asRepairWireMap()
    val restored = target.toEngineRepairState("target-repair")
    assertEquals(AcceptanceCriterionAccountingPolicy.VERIFIED_EVIDENCE, restored.accountingPolicy)
    val refused =
      assertNotNull(restored.completeRepairAwaitingTargetedVerification("repair-1", "output", DIGEST, "tree"))
    assertEquals(AcceptanceCriterionRepairStage.REPAIR_RESERVED, refused.stage)
    assertNotNull(refused.pendingClarificationQuestion)
    assertTrue(refused.completedMutations.isEmpty())
    assertEquals(target, restored.asEngineRepairWireMap())
  }

  private fun admission() =
    AcceptanceCriterionRepairAdmission(
      "catalog",
      "execution",
      DIGEST,
      listOf("first"),
      listOf(
        AcceptanceCriterionRepairCriterion(
          "AC-001",
          null,
          "first",
          1,
          AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
          AcceptanceCriterionValidationObligation.DEFERRED,
          true,
          "audit",
        ),
      ),
      emptyList(),
    )

  companion object {
    private val DIGEST = "a".repeat(64)
  }
}
