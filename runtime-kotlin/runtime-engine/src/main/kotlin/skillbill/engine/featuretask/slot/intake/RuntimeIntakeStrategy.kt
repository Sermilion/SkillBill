package skillbill.engine.featuretask.slot.intake

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.state.PhaseAgentStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public class RuntimeIntakeStrategy(private val planIntake: () -> FeatureTaskRuntimePlanIntake) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.INTAKE
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return DIRECTIVE
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return PhaseStepDescriptor(PhaseBindingFamily.Agent, PhaseStepHooks.None) { run, state ->
      settleIntake(run, state)
    }
  }

  private fun settleIntake(
    run: PhaseRun,
    state: PhaseAgentStepBinding,
  ): PhaseOutcome {
    state.requireAcceptedStep(run, strategyId)
    val request = run.request
    val settlement =
      planIntake().settle(
        request.workflowId,
        request.issueKey,
        request.planIntake,
        request.repoRoot,
        request.runInvariants.specReference,
      )
    val prose =
      when (settlement) {
        is PlanIntakeSettlement.Refused -> return PhaseOutcome.blocked(settlement.reason)
        is PlanIntakeSettlement.Kept -> "Intake kept the parent spec at ${settlement.specPath}."
        is PlanIntakeSettlement.Wrote -> "Intake wrote the parent spec at ${settlement.specPath}."
        is PlanIntakeSettlement.AlreadySettled ->
          "Intake was already settled for the parent spec at ${settlement.specPath}; nothing was written."
      }
    val iteration = state.nextStepIteration()
    val output =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          linkedMapOf<String, Any?>(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to run.phaseId,
            SharedPayloadKeys.STATUS to STATUS_COMPLETED,
            SharedPayloadKeys.SUMMARY to prose,
            SharedPayloadKeys.PRODUCED_OUTPUTS to linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to prose),
          ),
        ),
      )
    state.settleRuntimeAuthoredCompletion(run.phaseId, output)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output.canonicalJson, output, null),
    )
  }

  public companion object {
    public const val ID: String = "runtime-intake"

    private const val DIRECTIVE: String =
      "This phase does not launch an agent. The runtime keeps an operator spec that has a readable acceptance " +
        "list, rewrites one without it as prose intake at the same path, or seeds a spec from free-text " +
        "requirements. It writes only the parent spec, never a manifest or subtask spec."
  }
}
