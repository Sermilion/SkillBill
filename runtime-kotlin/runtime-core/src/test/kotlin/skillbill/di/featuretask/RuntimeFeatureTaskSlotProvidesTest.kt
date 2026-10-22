package skillbill.di.featuretask

import me.tatarka.inject.annotations.Component
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.reachableStrategyPairs
import skillbill.engine.featuretask.slot.registeredRunners
import skillbill.engine.featuretask.slot.reviewStrategyRunners
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.model.EnvironmentContext
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

  private fun strategiesWith(environment: Map<String, String>): PhaseStrategyLookup =
    RuntimeFeatureTaskSlotTestComponent::class.create(
      RuntimeContext(
        environment =
          EnvironmentContext(
            environment = environment,
            userHome = Files.createTempDirectory("skillbill-slot-provides"),
          ),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(),
        callbacks = OptionalCallbacks(),
      ),
    ).strategies

  @Test
  fun `model directive supplements default on and follow the setting`() {
    val unrelated = mapOf("SKILL_BILL_UNRELATED" to "x")

    assertTrue(strategiesWith(unrelated).modelDirectiveSupplements.enabled)
    assertTrue(strategiesWith(mapOf(ModelDirectiveSupplementsSetting.KEY to "true")).modelDirectiveSupplements.enabled)
    assertFalse(
      strategiesWith(mapOf(ModelDirectiveSupplementsSetting.KEY to "false")).modelDirectiveSupplements.enabled,
    )
  }

  @Test
  fun `an unrecognized supplements setting is refused with its typed code`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        strategiesWith(mapOf(ModelDirectiveSupplementsSetting.KEY to "off"))
      }

    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_MODEL_DIRECTIVE_SUPPLEMENTS_SETTING, failure.code)
  }

  @Test
  fun `production registry lists all quality_gate strategies`() {
    val registered = strategies.registry.strategies

    assertEquals(
      listOf(PackBuildStrategy.ID, AgentValidateStrategy.ID),
      registered.filter { it.slot == PhaseSlot.QUALITY_GATE }.map { it.strategyId },
    )
    assertEquals(PhaseSlot.entries.toSet(), registered.map { it.slot }.toSet())
  }

  @Test
  fun `production registrations are exactly the seventeen strategies live selection reaches`() {
    val registered = strategies.registry.strategies.map { it.slot to it.strategyId }

    assertEquals(registered.toSet(), strategies.reachableStrategyPairs())
    assertEquals(17, registered.size)
    assertFalse(registered.contains(PhaseSlot.CODE_REVIEW to "delegated"))
    assertFalse(registered.contains(PhaseSlot.PLAN to "agent-plan"))
  }

  @Test
  fun `every production registration and review strategy shares one phase runner`() {
    val runners = strategies.registry.registeredRunners()
    val reviewRunners = strategies.registry.reviewStrategyRunners()

    assertEquals(17, runners.size)
    assertEquals(3, reviewRunners.size)
    (runners + reviewRunners).forEach { assertSame(runners.first(), it) }
  }

  @Test
  fun `production selection resolves every slot of each definition for every selection fact`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      FeatureTaskRuntimeQualityGateSelection.entries.forEach { gate ->
        listOf(
          SkeletonDefinition.STANDALONE,
          SkeletonDefinition.GOAL_CHILD,
          SkeletonDefinition.PLAN,
          SkeletonDefinition.PR,
          SkeletonDefinition.MONITOR,
          SkeletonDefinition.STANDALONE_MONITOR,
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
        SkeletonDefinition.PLAN to setOf(RuntimeIntakeStrategy.ID, AgentPreplanStrategy.ID, PlanFanOutStrategy.ID),
        SkeletonDefinition.PR to setOf(RuntimeCommitStrategy.ID, PrDescriptionStrategy.ID, MonitorStrategy.ID),
        SkeletonDefinition.MONITOR to setOf(RuntimeCommitStrategy.ID, MonitorStrategy.ID),
        SkeletonDefinition.STANDALONE_MONITOR to
          setOf(StandaloneMonitorPublicationStrategy.ID, StandaloneMonitorStrategy.ID),
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
  fun `every skeleton holding the plan slot binds the one plan fan-out strategy`() {
    val holders = SkeletonDefinition.entries.filter { PhaseSlot.PLAN in it.slots }

    assertEquals(
      setOf(
        SkeletonDefinition.STANDALONE_REVISION_2,
        SkeletonDefinition.GOAL_CHILD_REVISION_2,
        SkeletonDefinition.STANDALONE,
        SkeletonDefinition.GOAL_CHILD_REVISION_3,
        SkeletonDefinition.GOAL_CHILD,
        SkeletonDefinition.PLAN,
      ),
      holders.toSet(),
    )
    holders.forEach { definition ->
      CodeReviewExecutionMode.entries.forEach { mode ->
        val facts = PhaseStrategySelectionFacts(definition, setOf(mode, FeatureTaskRuntimeQualityGateSelection.BUILD))
        val plan = strategies.strategyFor(PHASE_PLAN, facts)
        val planSubtasks = strategies.strategyFor(PHASE_PLAN_SUBTASKS, facts)

        assertEquals(PlanFanOutStrategy.ID, plan.strategyId, definition.id)
        assertEquals(PlanFanOutStrategy.ID, planSubtasks.strategyId, definition.id)
      }
    }
    assertEquals(
      listOf(PlanFanOutStrategy.ID),
      strategies.registry.strategies.filter { it.slot == PhaseSlot.PLAN }.map { it.strategyId },
    )
  }

  @Test
  fun `full-run review registers only the inline strategy and every accepted mode selects it`() {
    assertEquals(
      listOf(InlineReviewStrategy.ID),
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
  fun `the review definition selects delegated only for delegated mode and validation discovers project commands`() {
    val expected =
      mapOf(
        CodeReviewExecutionMode.AUTO to InlineStandaloneReviewStrategy.ID,
        CodeReviewExecutionMode.INLINE to InlineStandaloneReviewStrategy.ID,
        CodeReviewExecutionMode.DELEGATED to DelegatedStandaloneReviewStrategy.ID,
      )
    expected.forEach { (mode, strategyId) ->
      val facts = PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, setOf(mode))
      assertEquals(strategyId, strategies.strategyFor(PHASE_PRESENT_FINDINGS, facts).strategyId, "$mode")
    }
    assertEquals(
      setOf(PHASE_VALIDATE),
      strategies.selectedStepIds(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet())),
    )
    assertEquals(
      AgentValidateStrategy.ID,
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
