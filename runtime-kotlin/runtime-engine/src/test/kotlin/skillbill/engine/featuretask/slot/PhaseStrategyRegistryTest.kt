package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.PhaseStrategySelectionSlotMismatchError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.error.featuretask.UnregisteredPhaseStrategySelectionError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PhaseStrategyRegistryTest {
  @Test
  fun `registering one strategy id twice for a slot raises a typed error`() {
    val error =
      assertFailsWith<DuplicatePhaseStrategyError> {
        PhaseStrategyRegistry(listOf(reviewStrategy("inline"), reviewStrategy("inline")))
      }

    assertEquals("code_review" to "inline", error.slot to error.strategyId)
  }

  @Test
  fun `a strategy declaring a step outside its slot raises a typed error`() {
    val error =
      assertFailsWith<PhaseStrategyStepOutsideSlotError> {
        PhaseStrategyRegistry(
          listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "inline", listOf(PHASE_BUILD))),
        )
      }

    assertEquals(PHASE_BUILD, error.stepId)
  }

  @Test
  fun `a strategy with no steps raises a typed composition error`() {
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      PhaseStrategyRegistry(listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "empty", emptyList())))
    }
  }

  @Test
  fun `a strategy repeating a step raises a typed composition error`() {
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      PhaseStrategyRegistry(
        listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "duplicate", listOf(PHASE_REVIEW, PHASE_REVIEW))),
      )
    }
  }

  @Test
  fun `a strategy with an invalid semantic revision raises a typed composition error`() {
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      PhaseStrategyRegistry(listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "invalid-revision", listOf(PHASE_REVIEW), 0)))
    }
  }

  @Test
  fun `looking up an unregistered strategy raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error = assertFailsWith<UnknownPhaseStrategyError> { registry.strategy(PhaseSlot.CODE_REVIEW, "delegated") }

    assertEquals("delegated", error.strategyId)
  }

  @Test
  fun `an entry outside the owned steps raises a typed composition error`() {
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      PhaseStrategyRegistry(
        listOf(
          FakeStrategy(PhaseSlot.CODE_REVIEW, "bad-entry", listOf(PHASE_REVIEW), entryStep = PHASE_VERIFY_FINDINGS),
        ),
      )
    }
  }

  @Test
  fun `a missing definition binding cannot resolve an execution plan`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, emptyMap()))

    assertFailsWith<UnknownPhaseStrategyError> { lookup.executionPlan(facts(CodeReviewExecutionMode.INLINE)) }
    assertFailsWith<PhaseStrategySelectionSlotMismatchError> {
      PhaseStrategySelection(registry, mapOf(REVIEW_ONLY to emptyMap()))
    }
  }

  @Test
  fun `a selection naming an unregistered strategy raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error =
      assertFailsWith<UnregisteredPhaseStrategySelectionError> {
        PhaseStrategySelection(
          registry,
          mapOf(REVIEW_ONLY to mapOf(PhaseSlot.CODE_REVIEW to PhaseStrategyBinding.Fixed("parallel"))),
        )
      }

    assertEquals("parallel", error.strategyId)
  }

  @Test
  fun `a selection that binds a slot the definition lacks raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error =
      assertFailsWith<PhaseStrategySelectionSlotMismatchError> {
        PhaseStrategySelection(
          registry,
          mapOf(
            REVIEW_ONLY to
              mapOf(
                PhaseSlot.CODE_REVIEW to PhaseStrategyBinding.Fixed("inline"),
                PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed("inline"),
              ),
          ),
        )
      }

    assertEquals(REVIEW_ONLY.id to PhaseSlot.QUALITY_GATE.wireValue, error.definitionId to error.slot)
  }

  @Test
  fun `the lookup resolves the selected strategy and rejects a missing binding fact`() {
    val inline = reviewStrategy("inline")
    val registry = PhaseStrategyRegistry(listOf(inline))
    val selection =
      PhaseStrategySelection(
        registry,
        mapOf(
          REVIEW_ONLY to
            mapOf(
              PhaseSlot.CODE_REVIEW to
                PhaseStrategyBinding.ByFact(mapOf(CodeReviewExecutionMode.INLINE to "inline")),
            ),
        ),
      )
    val lookup = PhaseStrategyLookup(registry, selection)

    assertSame(
      inline,
      lookup.strategyFor(PHASE_VERIFY_FINDINGS, facts(CodeReviewExecutionMode.INLINE)),
    )
    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      lookup.strategyFor(PHASE_REVIEW, facts(CodeReviewExecutionMode.DELEGATED))
    }
  }

  private fun facts(mode: CodeReviewExecutionMode) = PhaseStrategySelectionFacts(REVIEW_ONLY, setOf(mode))

  private fun reviewStrategy(strategyId: String) =
    FakeStrategy(PhaseSlot.CODE_REVIEW, strategyId, PhaseSlot.CODE_REVIEW.steps)

  private class FakeStrategy(
    override val slot: PhaseSlot,
    override val strategyId: String,
    override val steps: List<String>,
    override val semanticRevision: Int = 1,
    override val entryStep: String = steps.firstOrNull().orEmpty(),
  ) : PhaseStrategy() {
    override fun policyFor(stepId: String): PhaseStepPolicy = PhaseStepPolicy(false, false, false, false, false)

    override fun directiveFor(stepId: String): String = error("unused")

    override fun runStep(
      run: PhaseRun,
      state: PhaseAcceptedStepExecution,
    ): PhaseOutcome = error("unused")
  }

  private companion object {
    val REVIEW_ONLY = SkeletonDefinition("review-only", listOf(PhaseSlot.CODE_REVIEW))
  }
}
