package skillbill.engine.featuretask.slot.implementation

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.isRetiredAuditGapLoop
import skillbill.engine.featuretask.slot.state.recordEnvelope
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class ImplementThenSimplifyStrategy : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = true,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.IMPLEMENTATION
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> ImplementationPromptSections.IMPLEMENT_DIRECTIVE
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY -> ImplementationPromptSections.SIMPLIFY_DIRECTIVE
      else -> throw UnknownPhaseStepError(stepId)
    }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT ->
        ImplementationPromptSections.implement(
          stepId,
          inputs,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY -> ImplementationPromptSections.simplify(stepId, inputs)
      else -> throw UnknownPhaseStepError(stepId)
    }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  override fun resumeRules(stepId: String): PhaseResumeRules {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) {
      ImplementResumeRules
    } else {
      PhaseResumeRules.None
    }
  }

  internal object ImplementResumeRules : PhaseResumeRules {
    override fun resumedRecord(
      record: FeatureTaskRuntimePhaseRecord,
      stripped: FeatureTaskRuntimePhaseRecord,
    ): FeatureTaskRuntimePhaseRecord =
      if (
        isRetiredAuditGapLoop(record.loopId) &&
        recordEnvelope(record)?.get(SharedPayloadKeys.STATUS) == WorkflowStepStatus.COMPLETED.wireValue
      ) {
        stripped.copy(status = WorkflowStepStatus.COMPLETED, blockedReason = null, failureDisposition = null)
      } else {
        stripped
      }

    override fun persistedBlockResume(
      reason: String,
      recentBlockedReasons: List<String?>,
    ): PhaseBlockResume =
      if ("exhausted the bounded implementation-continuation budget" in reason) {
        PhaseBlockResume.RELAUNCH_WITH_FRESH_BUDGET
      } else {
        PhaseBlockResume.DEFAULT
      }
  }

  companion object {
    const val ID = "implement-then-simplify"
  }
}
