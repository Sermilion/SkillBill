package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.PhaseStrategySelectionSlotMismatchError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.error.featuretask.UnregisteredPhaseStrategySelectionError
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition
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
  fun `looking up an unregistered strategy raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error = assertFailsWith<UnknownPhaseStrategyError> { registry.strategy(PhaseSlot.CODE_REVIEW, "delegated") }

    assertEquals("delegated", error.strategyId)
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
  fun `the lookup resolves the selected strategy and rejects an unmapped selection value`() {
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
    assertFailsWith<UnknownPhaseStrategyError> {
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
  ) : PhaseStrategy() {
    override val entryStep: String get() = steps.first()
    override val runner: PhaseRunner get() = error("unused")

    override fun policyFor(stepId: String): PhaseStepPolicy = error("unused")

    override fun directiveFor(stepId: String): String = error("unused")

    override fun runStep(
      run: PhaseRun,
      state: PhaseStepState,
    ): PhaseOutcome = error("unused")
  }

  private companion object {
    val REVIEW_ONLY = SkeletonDefinition("review-only", listOf(PhaseSlot.CODE_REVIEW))
  }
}
