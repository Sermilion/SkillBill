package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PhaseSlotFailureCode
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
  fun `registering one strategy id twice for a slot raises a defect`() {
    val error =
      assertFailsWith<IllegalStateException> {
        PhaseStrategyRegistry(listOf(reviewStrategy("inline"), reviewStrategy("inline")))
      }

    assertEquals("Phase slot 'code_review' registers strategy 'inline' more than once.", error.message)
  }

  @Test
  fun `a strategy declaring a step outside its slot raises a defect`() {
    val error =
      assertFailsWith<IllegalStateException> {
        PhaseStrategyRegistry(
          listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "inline", listOf(PHASE_BUILD))),
        )
      }

    assertEquals(
      "Phase strategy 'inline' for slot 'code_review' declares step '$PHASE_BUILD' outside that slot.",
      error.message,
    )
  }

  @Test
  fun `a strategy with no steps raises a typed composition error`() {
    assertFailsWith<IllegalArgumentException> {
      PhaseStrategyRegistry(listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "empty", emptyList())))
    }
  }

  @Test
  fun `a strategy repeating a step raises a typed composition error`() {
    assertFailsWith<IllegalArgumentException> {
      PhaseStrategyRegistry(
        listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "duplicate", listOf(PHASE_REVIEW, PHASE_REVIEW))),
      )
    }
  }

  @Test
  fun `a strategy with an invalid semantic revision raises a typed composition error`() {
    assertFailsWith<IllegalArgumentException> {
      PhaseStrategyRegistry(listOf(FakeStrategy(PhaseSlot.CODE_REVIEW, "invalid-revision", listOf(PHASE_REVIEW), 0)))
    }
  }

  @Test
  fun `looking up an unregistered strategy raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error = assertFailsWith<IllegalStateException> { registry.strategy(PhaseSlot.CODE_REVIEW, "delegated") }

    assertEquals("Phase slot 'code_review' has no strategy 'delegated'.", error.message)
  }

  @Test
  fun `an entry outside the owned steps raises a typed composition error`() {
    assertFailsWith<IllegalArgumentException> {
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

    val unknown =
      assertFailsWith<SkillBillRuntimeException> { lookup.executionPlan(facts(CodeReviewExecutionMode.INLINE)) }
    assertEquals(PhaseSlotFailureCode.UNKNOWN_PHASE_STRATEGY, unknown.code)
    assertFailsWith<IllegalStateException> {
      PhaseStrategySelection(registry, mapOf(REVIEW_ONLY to emptyMap()))
    }
  }

  @Test
  fun `a selection naming an unregistered strategy raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error =
      assertFailsWith<IllegalStateException> {
        PhaseStrategySelection(
          registry,
          mapOf(REVIEW_ONLY to mapOf(PhaseSlot.CODE_REVIEW to PhaseStrategyBinding.Fixed("parallel"))),
        )
      }

    assertEquals(
      "Phase strategy selection for slot 'code_review' names unregistered strategy 'parallel'.",
      error.message,
    )
  }

  @Test
  fun `a selection that binds a slot the definition lacks raises a typed error`() {
    val registry = PhaseStrategyRegistry(listOf(reviewStrategy("inline")))

    val error =
      assertFailsWith<IllegalStateException> {
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

    assertEquals(
      "Phase strategy selection for skeleton definition '${REVIEW_ONLY.id}' must bind exactly its slots; " +
        "slot '${PhaseSlot.QUALITY_GATE.wireValue}' is unbound or outside the definition.",
      error.message,
    )
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
    assertInvalidComposition {
      lookup.strategyFor(PHASE_REVIEW, facts(CodeReviewExecutionMode.DELEGATED))
    }
  }

  private fun facts(mode: CodeReviewExecutionMode) = PhaseStrategySelectionFacts(REVIEW_ONLY, setOf(mode))

  private fun assertInvalidComposition(block: () -> Unit) {
    val error = assertFailsWith<SkillBillRuntimeException>(block = block)
    assertEquals(PhaseSlotFailureCode.INVALID_STRATEGY_COMPOSITION, error.code)
  }

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
