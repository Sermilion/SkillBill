package skillbill.di.featuretask

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.model.EnvironmentContext
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeFeatureTaskSlotProvidesTest {
  private val strategies =
    RuntimeComponent::class.create(
      RuntimeContext(
        environment =
          EnvironmentContext(
            environment = emptyMap(),
            userHome = Files.createTempDirectory("skillbill-slot-provides"),
          ),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(),
        callbacks = OptionalCallbacks(),
      ),
    ).featureTaskRuntimeRunner.strategies

  @Test
  fun `production registry lists the two quality_gate strategies and a distinct runner each`() {
    val registered = strategies.registry.strategies

    assertEquals(
      listOf(PackBuildStrategy.ID, AgentValidateStrategy.ID),
      registered.filter { it.slot == PhaseSlot.QUALITY_GATE }.map { it.strategyId },
    )
    assertEquals(PhaseSlot.entries.toSet(), registered.map { it.slot }.toSet())
    assertEquals(registered.size, registered.map { it.runner }.toSet().size)
  }

  @Test
  fun `production selection resolves every slot of each definition for every selection fact`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      FeatureTaskRuntimeQualityGateSelection.entries.forEach { gate ->
        listOf(
          SkeletonDefinition.STANDALONE,
          SkeletonDefinition.GOAL_CHILD,
          SkeletonDefinition.PLAN,
          SkeletonDefinition.GOAL_PLANNING,
          SkeletonDefinition.IMPLEMENT,
          SkeletonDefinition.PR,
        ).forEach { definition ->
          val facts = PhaseStrategySelectionFacts(definition, setOf(mode, gate))
          definition.stepIds.forEach { step ->
            assertEquals(PhaseSlot.slotForStep(step), strategies.strategyFor(step, facts).slot)
          }
        }
      }
    }
  }

  @Test
  fun `plan, implement, and pr select the existing preplan, plan, implement, and pr strategies`() {
    val expected =
      mapOf(
        SkeletonDefinition.PLAN to setOf(AgentPreplanStrategy.ID, AgentPlanStrategy.ID),
        SkeletonDefinition.IMPLEMENT to setOf(ImplementThenSimplifyStrategy.ID),
        SkeletonDefinition.PR to setOf(PrDescriptionStrategy.ID),
      )
    expected.forEach { (definition, strategyIds) ->
      val facts = PhaseStrategySelectionFacts(definition, emptySet())
      assertEquals(
        strategyIds,
        definition.stepIds.map { step -> strategies.strategyFor(step, facts).strategyId }.toSet(),
        definition.id,
      )
    }
  }

  @Test
  fun `goal planning runs the shared preplan and fans the plan out over the agent plan`() {
    val facts = PhaseStrategySelectionFacts(SkeletonDefinition.GOAL_PLANNING, emptySet())

    assertEquals(
      listOf(AgentPreplanStrategy.ID, GoalPlanFanOutStrategy.ID),
      SkeletonDefinition.GOAL_PLANNING.stepIds.map { step -> strategies.strategyFor(step, facts).strategyId },
    )
    assertEquals(
      listOf(AgentPlanStrategy.ID, GoalPlanFanOutStrategy.ID),
      strategies.registry.strategies.filter { it.slot == PhaseSlot.PLAN }.map { it.strategyId },
    )
  }

  @Test
  fun `delegated review is registered yet every accepted mode still selects inline`() {
    assertEquals(
      listOf(InlineReviewStrategy.ID, DelegatedReviewStrategy.ID),
      strategies.registry.strategies.filter { it.slot == PhaseSlot.CODE_REVIEW }.map { it.strategyId },
    )
    CodeReviewExecutionMode.entries.forEach { mode ->
      listOf(SkeletonDefinition.STANDALONE, SkeletonDefinition.GOAL_CHILD).forEach { definition ->
        val facts = PhaseStrategySelectionFacts(definition, setOf(mode, FeatureTaskRuntimeQualityGateSelection.BUILD))
        assertEquals(InlineReviewStrategy.ID, strategies.strategyFor(PHASE_REVIEW, facts).strategyId, "$mode")
      }
    }
  }

  @Test
  fun `the review definition selects delegated only for delegated mode and validation runs pack-build`() {
    val expected =
      mapOf(
        CodeReviewExecutionMode.AUTO to InlineReviewStrategy.ID,
        CodeReviewExecutionMode.INLINE to InlineReviewStrategy.ID,
        CodeReviewExecutionMode.DELEGATED to DelegatedReviewStrategy.ID,
      )
    expected.forEach { (mode, strategyId) ->
      val facts = PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, setOf(mode))
      assertEquals(strategyId, strategies.strategyFor(PHASE_REVIEW, facts).strategyId, "$mode")
    }
    assertEquals(
      setOf(PHASE_BUILD),
      strategies.selectedStepIds(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet())),
    )
  }

  @Test
  fun `the goal child runs the stamped gate and the standalone run always validates`() {
    fun selectedGateSteps(
      definition: SkeletonDefinition,
      gate: FeatureTaskRuntimeQualityGateSelection?,
    ): Set<String> =
      strategies.selectedStepIds(
        PhaseStrategySelectionFacts(definition, setOfNotNull(CodeReviewExecutionMode.DEFAULT, gate)),
      ) intersect setOf(PHASE_BUILD, PHASE_VALIDATE)

    assertEquals(
      setOf(PHASE_BUILD),
      selectedGateSteps(SkeletonDefinition.GOAL_CHILD, FeatureTaskRuntimeQualityGateSelection.BUILD),
    )
    assertEquals(
      setOf(PHASE_VALIDATE),
      selectedGateSteps(SkeletonDefinition.GOAL_CHILD, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
    )
    assertEquals(setOf(PHASE_VALIDATE), selectedGateSteps(SkeletonDefinition.STANDALONE, null))
  }
}
