package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhaseStrategyTraversalTest {
  @Test
  fun `optional definition projection preserves dispatch and rejects a missing mandatory step`() {
    val definition =
      SkeletonDefinition(
        "implement-only",
        listOf(PhaseSlot.IMPLEMENTATION),
        stepIds = listOf(PHASE_IMPLEMENT),
      )
    val strategy =
      CompositionTestStrategy(
        PhaseSlot.IMPLEMENTATION,
        "implementation",
        listOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY),
        optionalSteps = setOf(PHASE_SIMPLIFY),
      )
    val facts = PhaseStrategySelectionFacts(definition, emptySet())
    val lookup = lookup(definition, strategy)
    val plan = lookup.executionPlan(facts)

    assertEquals(listOf(PHASE_IMPLEMENT), plan.traversal.forwardPhaseIds)
    assertSame(strategy, lookup.strategyFor(PHASE_IMPLEMENT, plan))
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.strategyFor(PHASE_SIMPLIFY, plan) }
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      lookup(
        definition,
        CompositionTestStrategy(strategy.slot, strategy.strategyId, strategy.steps),
      ).executionPlan(facts)
    }
  }

  @Test
  fun `definition projection cannot silently remove the selected entry`() {
    val definition =
      SkeletonDefinition("simplify-only", listOf(PhaseSlot.IMPLEMENTATION), stepIds = listOf(PHASE_SIMPLIFY))
    val strategy =
      CompositionTestStrategy(
        PhaseSlot.IMPLEMENTATION,
        "implementation",
        listOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY),
        optionalSteps = setOf(PHASE_IMPLEMENT),
      )

    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      lookup(definition, strategy).executionPlan(PhaseStrategySelectionFacts(definition, emptySet()))
    }
  }

  @Test
  fun `selected gates and remediation cannot reference steps omitted by the strategy`() {
    listOf(listOf(PHASE_REVIEW, PHASE_IMPLEMENT_FIX), listOf(PHASE_REVIEW, PHASE_VERIFY_FINDINGS)).forEach { steps ->
      val strategy = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "incomplete-review", steps)
      assertFailsWith<InvalidPhaseStrategyCompositionError> {
        lookup(SkeletonDefinition.REVIEW, strategy)
          .executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, emptySet()))
      }
    }
  }

  @Test
  fun `loop-only remediation is accepted but unreachable steps and entries are rejected`() {
    val strategy = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "review", PhaseSlot.CODE_REVIEW.steps)
    val lookup = lookup(SkeletonDefinition.REVIEW, strategy)
    val facts = PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, emptySet())
    val plan = lookup.executionPlan(facts)

    assertEquals(setOf(PHASE_IMPLEMENT_FIX), plan.traversal.loopOnlyPhaseIds)
    assertTrue(plan.traversal.backwardEdges.any { it.destinationPhaseId == PHASE_IMPLEMENT_FIX })
    assertSame(strategy, lookup.strategyFor(PHASE_IMPLEMENT_FIX, plan))
    val remediationChain =
      plan.traversal.copy(
        loopOnlyPhaseIds = setOf(PHASE_VERIFY_FINDINGS, PHASE_IMPLEMENT_FIX),
        backwardEdges =
          plan.traversal.backwardEdges.map {
            it.copy(fromPhaseId = PHASE_REVIEW, destinationPhaseId = PHASE_VERIFY_FINDINGS)
          },
        loopOnlySuccessors = mapOf(PHASE_VERIFY_FINDINGS to PHASE_IMPLEMENT_FIX),
      )
    assertEquals(remediationChain, lookup.validateTraversalOverride(facts, remediationChain))
    val malformed =
      listOf(
        plan.traversal.copy(backwardEdges = emptyList()),
        plan.traversal.copy(loopOnlyPhaseIds = setOf(PHASE_REVIEW, PHASE_IMPLEMENT_FIX)),
        FeatureTaskRuntimeTransitionDeclaration(listOf(PHASE_REVIEW)),
      )
    malformed.forEach { declaration ->
      assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.validateTraversalOverride(facts, declaration) }
      assertFailsWith<InvalidPhaseStrategyCompositionError> { plan.withTraversal(declaration) }
    }
  }

  @Test
  fun `mutated definition order cannot put an entry before its required gate`() {
    val strategy = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "review", PhaseSlot.CODE_REVIEW.steps)
    val steps = PhaseSlot.CODE_REVIEW.steps.toMutableList()
    val definition =
      SkeletonDefinition(
        "invalid-review-order",
        listOf(PhaseSlot.CODE_REVIEW),
        stepIds = steps,
      )
    steps[1] = PHASE_IMPLEMENT_FIX
    steps[2] = PHASE_VERIFY_FINDINGS
    val lookup = lookup(definition, strategy)

    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      lookup.executionPlan(PhaseStrategySelectionFacts(definition, emptySet()))
    }
  }

  @Test
  fun `traversal overrides reject conflicting remediation dispatch and gate requirements`() {
    val strategy = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "review", PhaseSlot.CODE_REVIEW.steps)
    val lookup = lookup(SkeletonDefinition.REVIEW, strategy)
    val facts = PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, emptySet())
    val traversal = lookup.executionPlan(facts).traversal
    val edge = traversal.backwardEdges.single()
    val gate = traversal.entryGates.single()
    val malformed =
      listOf(
        traversal.copy(backwardEdges = listOf(edge, edge.copy(loopId = "other-remediation"))),
        traversal.copy(backwardEdges = listOf(edge, edge.copy(fromPhaseId = PHASE_REVIEW))),
        traversal.copy(entryGates = listOf(gate, gate.copy(requiredVerdict = edge.triggeringVerdict))),
      )

    malformed.forEach { declaration ->
      assertFailsWith<InvalidPhaseStrategyCompositionError> {
        lookup.validateTraversalOverride(facts, declaration)
      }
    }
  }

  @Test
  fun `dispatch refuses a strategy whose policy changed after resolution`() {
    val strategy = CompositionTestStrategy(PhaseSlot.IMPLEMENTATION, "implementation", PhaseSlot.IMPLEMENTATION.steps)
    val definition = SkeletonDefinition("implementation", listOf(PhaseSlot.IMPLEMENTATION))
    val lookup = lookup(definition, strategy)
    val plan = lookup.executionPlan(PhaseStrategySelectionFacts(definition, emptySet()))
    assertSame(strategy, lookup.strategyFor(PHASE_IMPLEMENT, plan))
    strategy.stepPolicy = strategy.stepPolicy.copy(singleAgentSession = true)

    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.strategyFor(PHASE_IMPLEMENT, plan) }
  }

  @Test
  fun `accepted plan freezes registration collections and traversal overrides`() {
    val steps = mutableListOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY)
    val strategy = CompositionTestStrategy(PhaseSlot.IMPLEMENTATION, "implementation", steps)
    val definition = SkeletonDefinition("implementation", listOf(PhaseSlot.IMPLEMENTATION))
    val lookup = lookup(definition, strategy)
    val facts = PhaseStrategySelectionFacts(definition, emptySet())
    val plan = lookup.executionPlan(facts)
    val forward = steps.toMutableList()
    val accepted =
      plan.withTraversal(
        lookup.validateTraversalOverride(facts, FeatureTaskRuntimeTransitionDeclaration(forward)),
      )
    steps.clear()
    forward.clear()

    assertEquals(listOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY), accepted.traversal.forwardPhaseIds)
    assertEquals(listOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY), accepted.selectedStrategies.single().steps)
    assertEquals(setOf(PHASE_IMPLEMENT, PHASE_SIMPLIFY), accepted.dispatchStrategyByStep.keys)
    assertFailsWith<UnsupportedOperationException> {
      (accepted.selectedStrategies.single().steps as MutableList<String>).clear()
    }
  }

  private fun lookup(
    definition: SkeletonDefinition,
    strategy: PhaseStrategy,
  ): PhaseStrategyLookup {
    val registry = PhaseStrategyRegistry(listOf(strategy))
    return PhaseStrategyLookup(
      registry,
      PhaseStrategySelection(
        registry,
        mapOf(
          definition to mapOf(strategy.slot to PhaseStrategyBinding.Fixed(strategy.strategyId)),
        ),
      ),
    )
  }
}

internal class CompositionTestStrategy(
  override val slot: PhaseSlot,
  override val strategyId: String,
  override val steps: List<String>,
  override val optionalSteps: Set<String> = emptySet(),
  override val entryStep: String = steps.first(),
) : PhaseStrategy() {
  var stepPolicy = PhaseStepPolicy(false, false, false, false, false)

  override fun policyFor(stepId: String) = stepPolicy

  override fun directiveFor(stepId: String): String = error("Composition must not request a launch directive")

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = error("Composition must not execute a step")
}
