package skillbill.engine.featuretask.slot.writehistory

import skillbill.engine.directive.directiveResource
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.withMeasuredFacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class BoundaryHistoryStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
        PhaseStepPolicy(
          mutating = false,
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
      stepContext = STEP_CONTEXT,
      valueContent = VALUE_CONTENT,
    )

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks = MeasuredHistoryHooks

  private object MeasuredHistoryHooks : PhaseStepHooks {
    override fun acceptedOutput(
      context: PhaseStepOutputContext,
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    ): NormalizedFeatureTaskRuntimePhaseOutput =
      attested.withMeasuredFacts(WriteHistoryMeasurement(context.diagnostics).facts(capture.fileManifest))
  }

  companion object {
    const val ID = "boundary-history"
    internal const val HISTORY_RULES_RESOURCE =
      "/skillbill/engine/featuretask/slot/writehistory/boundary-history-directive.md"
    internal const val DECISIONS_RULES_RESOURCE =
      "/skillbill/engine/featuretask/slot/writehistory/boundary-decisions-directive.md"

    private val STEP_CONTEXT: String by lazy {
      listOf(
        directiveResource(HISTORY_RULES_RESOURCE).trimEnd(),
        directiveResource(DECISIONS_RULES_RESOURCE).trimEnd(),
        BoundaryMemoryPromptRules.section,
      ).joinToString("\n\n")
    }

    private const val DIRECTIVE: String =
      "Apply the boundary history and decision rules below to the implemented runtime change; " +
        "do not forward implementation or validation reports."

    private const val VALUE_CONTENT: String =
      "Carry, as prose, whether history was written or skipped and the decisions recorded. The runtime measures\n" +
        "the changed history paths itself before and after this step; do not restate them as evidence."
  }
}
