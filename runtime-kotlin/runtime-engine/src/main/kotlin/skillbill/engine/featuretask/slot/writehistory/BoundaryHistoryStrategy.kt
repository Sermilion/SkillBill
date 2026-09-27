package skillbill.engine.featuretask.slot.writehistory

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.withMeasuredFacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class BoundaryHistoryStrategy(override val runner: PhaseRunner) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
        PhaseStepPolicy(
          mutating = false,
          relaunchOnInvalidOutput = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.WRITE_HISTORY
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = directiveFor(stepId),
      valueContent = VALUE_CONTENT,
    )

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseStepState,
  ): PhaseOutcome = runAgentStep(run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks = MeasuredHistoryHooks

  private object MeasuredHistoryHooks : PhaseStepHooks {
    override fun acceptedOutput(
      context: PhaseAttemptEnvironment,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): NormalizedFeatureTaskRuntimePhaseOutput =
      attested.withMeasuredFacts(WriteHistoryMeasurement(context.diagnostics).facts(capture.fileManifest))
  }

  companion object {
    const val ID = "boundary-history"

    private const val DIRECTIVE: String =
      "Invoke bill-boundary-history inline and apply its write/skip rules for the implemented runtime change; " +
        "do not forward implementation or validation reports."

    private const val VALUE_CONTENT: String =
      "Carry, as prose, whether history was written or skipped and the decisions recorded. The runtime measures\n" +
        "the changed history paths itself before and after this step; do not restate them as evidence."
  }
}
