package skillbill.di.featuretask

import me.tatarka.inject.annotations.Component
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.model.EnvironmentContext
import skillbill.review.context.model.execution.CodeReviewExecutionMode
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
    RuntimeFeatureTaskSlotTestComponent::class.create(
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
    ).strategies

  @Test
  fun `production registry lists all quality_gate strategies`() {
    val registered = strategies.registry.strategies

    assertEquals(
      listOf(PackBuildStrategy.ID, PackValidationStrategy.ID, AgentValidateStrategy.ID),
      registered.filter { it.slot == PhaseSlot.QUALITY_GATE }.map { it.strategyId },
    )
    assertEquals(PhaseSlot.entries.toSet(), registered.map { it.slot }.toSet())
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
          SkeletonDefinition.PR,
        ).forEach { definition ->
          val facts = PhaseStrategySelectionFacts(definition, setOf(mode, gate))
          strategies.selectedStepIds(facts).forEach { step ->
            assertEquals(PhaseSlot.slotForStep(step), strategies.strategyFor(step, facts).slot)
          }
        }
      }
    }
  }

  @Test
  fun `plan and pr select the existing planning and publishing strategies`() {
    val expected =
      mapOf(
        SkeletonDefinition.PLAN to setOf(AgentPreplanStrategy.ID, AgentPlanStrategy.ID),
        SkeletonDefinition.PR to setOf(RuntimeCommitStrategy.ID, PrDescriptionStrategy.ID),
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
  fun `the review definition selects delegated only for delegated mode and validation runs pack validation`() {
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
      setOf(PHASE_VALIDATE),
      strategies.selectedStepIds(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet())),
    )
    assertEquals(
      PackValidationStrategy.ID,
      strategies.strategyFor(
        PHASE_VALIDATE,
        PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet()),
      ).strategyId,
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

@Component
internal abstract class RuntimeFeatureTaskSlotTestComponent(
  context: RuntimeContext,
) : RuntimeComponent(context) {
  abstract override val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService

  abstract val strategies: PhaseStrategyLookup
}
