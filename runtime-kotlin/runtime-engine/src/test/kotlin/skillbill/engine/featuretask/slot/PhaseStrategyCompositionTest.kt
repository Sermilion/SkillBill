package skillbill.engine.featuretask.slot

import skillbill.engine.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.FILE_MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.GENERATION_SCOPED
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.READ_ONLY_IDLE
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.RELAUNCH
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.SINGLE
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.pullrequest.PullRequestReadinessGate
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.promptComposerBriefingFor
import skillbill.infrastructure.contracts.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseStrategyCompositionTest {
  private val runner =
    object : PhaseRunner {
      override fun run(
        input: PhaseStepInput,
        state: PhaseLaunchState,
      ): PhaseStepOutput = error("Policy lookups must not launch a step.")
    }

  private val strategies =
    listOf(
      AgentPreplanStrategy(runner),
      AgentPlanStrategy(runner),
      ImplementThenSimplifyStrategy(runner),
      AcceptanceAuditStrategy(runner),
      InlineReviewStrategy(runner),
      PackBuildStrategy(runner),
      PackValidationStrategy(runner),
      AgentValidateStrategy(runner),
      BoundaryHistoryStrategy(runner),
      RuntimeCommitStrategy(runner),
      PrDescriptionStrategy(
        runner,
        UnavailablePullRequestIdentityLookup,
        PullRequestReadinessGate(AbsentReadinessEvidence, NoopRuntimeDiagnostics),
        LocalPullRequestTemplateFiles,
      ),
    )

  @Test
  fun `strategies keep audit inspection read only and repairs mutating`() {
    val actual = strategies.flatMap { strategy -> strategy.steps.map { it to strategy.policyFor(it) } }.toMap()

    assertEquals(EXPECTED_POLICIES, actual)
  }

  @Test
  fun `every selectable step without a structured contract settles with the uniform output`() {
    val registry = PhaseStrategyRegistry(strategies)
    val selectable =
      testPhaseStrategyBindings()
        .values
        .flatMap { bindings -> bindings.flatMap { (slot, binding) -> binding.strategyIds.map { slot to it } } }
        .map { (slot, strategyId) -> registry.strategy(slot, strategyId) }
        .distinct()
        .flatMap { strategy -> strategy.steps.map { step -> strategy to step } }

    val validator = FeatureTaskRuntimePhaseOutputSchemaValidator()
    selectable.forEach { (strategy, step) ->
      val inputs =
        FeatureTaskRuntimePhasePromptComposeInputs(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(step))
      val sections = strategy.promptSections(step, inputs)
      when {
        !sections.settles -> Unit
        sections.outputContract == null -> {
          assertTrue(ProsePhaseOutputSynthesizer.isProsePhase(step), "$step must be accepted as a prose step")
          validator.normalizePhaseOutput(MINIMAL_FINAL_OBJECT, step)
        }
        else -> assertEquals(PhaseSlot.CODE_REVIEW, strategy.slot, "$step carries a structured output contract")
      }
    }
  }

  @Test
  fun `standalone validation selects pack validation while goal gate selections remain distinct`() {
    fun selected(
      definition: SkeletonDefinition,
      gate: FeatureTaskRuntimeQualityGateSelection,
    ): String? =
      SkeletonStrategyBindings.bindings
        .getValue(definition)
        .getValue(PhaseSlot.QUALITY_GATE)
        .resolve(PhaseStrategySelectionFacts(definition, setOf(gate)))

    assertEquals(
      PackValidationStrategy.ID,
      selected(SkeletonDefinition.VALIDATION, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
    )
    assertEquals(
      AgentValidateStrategy.ID,
      selected(SkeletonDefinition.GOAL_CHILD, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
    )
    assertEquals(
      PackBuildStrategy.ID,
      selected(SkeletonDefinition.GOAL_CHILD, FeatureTaskRuntimeQualityGateSelection.BUILD),
    )
  }

  private companion object {
    const val MINIMAL_FINAL_OBJECT = """{"status": "completed", "summary": "Done.", "value": "The step finished."}"""

    val EXPECTED_POLICIES =
      mapOf(
        PHASE_PREPLAN to policy(RELAUNCH),
        PHASE_PLAN to policy(RELAUNCH),
        PHASE_IMPLEMENT to policy(MUTATING, RELAUNCH, FILE_MUTATING).extendingInventory(),
        PHASE_SIMPLIFY to
          policy(MUTATING, RELAUNCH, SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_AUDIT to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_AUDIT_IMPLEMENT_FIX to policy(MUTATING, RELAUNCH, FILE_MUTATING).extendingInventory(),
        PHASE_REVIEW to policy(RELAUNCH, FILE_MUTATING, GENERATION_SCOPED),
        PHASE_VERIFY_FINDINGS to policy(RELAUNCH, READ_ONLY_IDLE, FILE_MUTATING),
        PHASE_IMPLEMENT_FIX to
          policy(MUTATING, RELAUNCH, FILE_MUTATING, GENERATION_SCOPED).extendingInventory(),
        PHASE_BUILD to policy(RELAUNCH, FILE_MUTATING),
        PHASE_VALIDATE to
          policy(SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_WRITE_HISTORY to policy(FILE_MUTATING).extendingInventory(),
        PHASE_COMMIT_PUSH to policy(FILE_MUTATING),
        PHASE_PR to policy(FILE_MUTATING),
      )

    fun policy(vararg traits: PolicyTrait): PhaseStepPolicy {
      val set = traits.toSet()
      return PhaseStepPolicy(
        mutating = MUTATING in set,
        relaunchOnInvalidOutput = RELAUNCH in set,
        singleAgentSession = SINGLE in set,
        readOnlyIdle = READ_ONLY_IDLE in set,
        fileMutating = FILE_MUTATING in set,
        generationScoped = GENERATION_SCOPED in set,
      )
    }

    fun PhaseStepPolicy.extendingInventory() = copy(extendsOwnedInventory = true)
  }

  private enum class PolicyTrait { MUTATING, RELAUNCH, SINGLE, READ_ONLY_IDLE, FILE_MUTATING, GENERATION_SCOPED }
}
