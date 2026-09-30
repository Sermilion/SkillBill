package skillbill.workflow.taskruntime.model.skeleton

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolvedPhaseExecutionPlanImmutabilityTest {
  @Test
  fun `registration and traversal input mutations cannot change a resolved plan`() {
    val steps = mutableListOf("implement", "simplify")
    val identity = ResolvedPhaseStrategyIdentity(PhaseSlot.IMPLEMENTATION, "implement-simplify", 1, steps, "implement")
    val strategies = mutableListOf(identity)
    val owner = ResolvedPhaseStrategyDispatch(identity.slot, identity.strategyId, identity.semanticRevision)
    val dispatch = steps.associateWith { owner }.toMutableMap()
    val policies = steps.associateWith { "step-policy-v1:$it" }.toMutableMap()
    val resumes = steps.associateWith { "resume-v1:$it" }.toMutableMap()
    val forward = steps.toMutableList()
    val edge =
      FeatureTaskRuntimeBackwardEdge("simplify", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, "implement", "retry", 1)
    val edges = mutableListOf(edge)
    val gate = FeatureTaskRuntimePhaseEntryGate("simplify", "implement", FeatureTaskRuntimeVerdict.SATISFIED)
    val gates = mutableListOf(gate)
    val loopOnly = mutableSetOf<String>()
    val successors = mutableMapOf<String, String>()
    val plan =
      ResolvedPhaseExecutionPlan(
        definitionId = "implementation",
        definitionSemanticRevision = 1,
        selectedStrategies = strategies,
        reviewSelection = null,
        qualityGateSelection = null,
        traversal = FeatureTaskRuntimeTransitionDeclaration(forward, edges, loopOnly, gates, successors),
        dispatchStrategyByStep = dispatch,
        stepPolicyIdentities = policies,
        resumeInterpretationIdentities = resumes,
      )

    steps.clear()
    strategies.clear()
    dispatch.clear()
    policies.clear()
    resumes.clear()
    forward.reverse()
    edges.clear()
    gates.clear()
    loopOnly.add("simplify")
    successors["simplify"] = "implement"

    assertEquals(listOf("implement", "simplify"), plan.selectedStrategies.single().steps)
    assertEquals(setOf("implement", "simplify"), plan.selectedStepIds)
    assertEquals(setOf("implement"), plan.selectedEntryStepIds)
    assertEquals(listOf(PhaseSlot.IMPLEMENTATION), plan.selectedSlots)
    assertEquals(mapOf("implement" to owner, "simplify" to owner), plan.dispatchStrategyByStep)
    assertEquals(
      mapOf("implement" to "step-policy-v1:implement", "simplify" to "step-policy-v1:simplify"),
      plan.stepPolicyIdentities,
    )
    assertEquals(
      mapOf("implement" to "resume-v1:implement", "simplify" to "resume-v1:simplify"),
      plan.resumeInterpretationIdentities,
    )
    assertEquals(listOf("implement", "simplify"), plan.traversal.forwardPhaseIds)
    assertEquals(listOf(edge), plan.traversal.backwardEdges)
    assertEquals(listOf(gate), plan.traversal.entryGates)
    assertEquals(emptySet(), plan.traversal.loopOnlyPhaseIds)
    assertEquals(emptyMap(), plan.traversal.loopOnlySuccessors)

    assertFailsWith<UnsupportedOperationException> {
      (plan.selectedStrategies.single().steps as MutableList<String>).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (plan.dispatchStrategyByStep as MutableMap<String, ResolvedPhaseStrategyDispatch>).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (plan.traversal.forwardPhaseIds as MutableList<String>).reverse()
    }
  }
}
