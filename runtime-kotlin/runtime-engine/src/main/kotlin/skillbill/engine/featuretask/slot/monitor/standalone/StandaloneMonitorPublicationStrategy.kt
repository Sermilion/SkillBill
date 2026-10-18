package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseStandalonePublicationStepBinding
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class StandaloneMonitorPublicationStrategy(
  private val service: StandaloneMonitorService,
) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.COMMIT_PUSH
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind =
    PhaseExecutionBindingKind.STANDALONE_PUBLICATION.also { policies.policyOf(stepId) }

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return StandaloneMonitorDirectives.PUBLICATION
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    state.requireAcceptedStep(run, strategyId)
    return (
      state as? PhaseStandalonePublicationStepBinding
        ?: error("Standalone publication requires its accepted execution binding.")
    ).runStandalonePublication(run, service.operations)
  }

  companion object {
    const val ID = "standalone-monitor-publication"
  }
}
