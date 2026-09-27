package skillbill.engine.featuretask.slot

import skillbill.engine.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
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
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.promptComposerBriefingFor
import skillbill.infrastructure.contracts.FeatureTaskRuntimePhaseOutputSchemaValidator
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
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
  fun `strategies declare the pre-change step policy table`() {
    val actual = strategies.flatMap { strategy -> strategy.steps.map { it to strategy.policyFor(it) } }.toMap()

    assertEquals(EXPECTED_POLICIES, actual)
  }

  @Test
  fun `every selectable step without a structured contract settles with the uniform output`() {
    val registry = PhaseStrategyRegistry(strategies)
    val selectable =
      testPhaseStrategyBindings().values
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

  private companion object {
    const val MINIMAL_FINAL_OBJECT = """{"status": "completed", "summary": "Done.", "value": "The step finished."}"""

    val EXPECTED_POLICIES =
      mapOf(
        PHASE_PREPLAN to policy(relaunch = true),
        PHASE_PLAN to policy(relaunch = true),
        PHASE_IMPLEMENT to policy(mutating = true, relaunch = true, fileMutating = true).extendingInventory(),
        PHASE_SIMPLIFY to
          policy(mutating = true, relaunch = true, single = true, fileMutating = true).extendingInventory(),
        PHASE_AUDIT to policy(single = true, fileMutating = true),
        PHASE_REVIEW to policy(relaunch = true, fileMutating = true, generationScoped = true),
        PHASE_VERIFY_FINDINGS to policy(relaunch = true, readOnlyIdle = true, fileMutating = true),
        PHASE_IMPLEMENT_FIX to
          policy(mutating = true, relaunch = true, fileMutating = true, generationScoped = true).extendingInventory(),
        PHASE_BUILD to policy(relaunch = true, fileMutating = true),
        PHASE_VALIDATE to
          policy(relaunch = true, fileMutating = true).copy(outputGateAttempts = 2).extendingInventory(),
        PHASE_WRITE_HISTORY to policy(fileMutating = true).extendingInventory(),
        PHASE_COMMIT_PUSH to policy(fileMutating = true),
        PHASE_PR to policy(fileMutating = true),
      )

    fun policy(
      mutating: Boolean = false,
      relaunch: Boolean = false,
      single: Boolean = false,
      readOnlyIdle: Boolean = false,
      fileMutating: Boolean = false,
      generationScoped: Boolean = false,
    ) = PhaseStepPolicy(mutating, relaunch, single, readOnlyIdle, fileMutating, generationScoped)

    fun PhaseStepPolicy.extendingInventory() = copy(extendsOwnedInventory = true)
  }
}
