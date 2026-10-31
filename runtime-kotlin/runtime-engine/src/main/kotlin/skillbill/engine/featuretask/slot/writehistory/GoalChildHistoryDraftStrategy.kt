package skillbill.engine.featuretask.slot.writehistory

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseAcceptedOutputPersistence
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public class GoalChildHistoryDraftStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
          extendsOwnedInventory = false,
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
      stepContext = CONTEXT,
      valueContent = VALUE_CONTENT,
    )

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
  }

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return PhaseStepDescriptor(
      PhaseBindingFamily.Agent,
      DraftHooks,
    ) { run,
        state,
      ->
      runAgentStep(
        run,
        state,
      )
    }
  }

  private object DraftHooks : PhaseStepHooks<PhaseAcceptedStepExecution>, PhaseAcceptedOutputPersistence {
    override fun persistAcceptedOutput(
      recorder: PhaseRunGoal,
      run: PhaseRun,
      output: NormalizedFeatureTaskRuntimePhaseOutput,
    ): String? = GoalChildHistoryDraftOutputPersistence.save(recorder, run, output)
  }

  public companion object {
    public const val ID: String = "goal-child-history-draft"
    private const val DIRECTIVE =
      "Prepare a durable history draft for the parent goal. " +
        "Do not edit, create, or delete project history or decision files."
    private const val CONTEXT =
      "Use the boundary history and decision rules to determine whether this change merits a durable record. " +
        "Use the current diff and supplied spec as evidence. " +
        "Return only the proposed text; publication happens after goal validation."
    private const val VALUE_CONTENT =
      "Return exactly one JSON object as the accepted phase value. " +
        "For a skip use {\"disposition\":\"skip\",\"reason\":\"...\"}. " +
        "For a draft use {\"disposition\":\"draft\",\"boundary\":\"...\",\"history\":\"...\",\"decisions\":\"...\"}. " +
        "Draft text must be proposed prose and must not be written to files."
  }
}
