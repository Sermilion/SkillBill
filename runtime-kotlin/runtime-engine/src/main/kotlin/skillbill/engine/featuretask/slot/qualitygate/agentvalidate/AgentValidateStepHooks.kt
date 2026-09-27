package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object AgentValidateStepHooks : PhaseStepHooks {
  override val checksImmediateConsumerProjection: Boolean = true

  override val blockedOutputDisposition: FeatureTaskRuntimeFailureDisposition
    get() = FeatureTaskRuntimeFailureDisposition.RETRYABLE

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.BLOCKED) {
      return PhaseStepOutputCheck.Accept
    }
    val verdict = outputMap[SharedPayloadKeys.VERDICT] as? String
    return when (verdict) {
      FeatureTaskRuntimeVerdict.PROGRESS.wireValue -> PhaseStepOutputCheck.ContinueRepair(remainingFailures(outputMap))
      FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue -> noProgressBlock(run.phaseId)
      else -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "Blocked '${run.phaseId}' output of workflow '${run.request.workflowId}' carried " +
            "${verdict?.let { "unknown verdict '$it'" } ?: "no verdict"}; counted as " +
            "${FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue}.",
        )
        noProgressBlock(run.phaseId)
      }
    }
  }

  private fun noProgressBlock(stepId: String): PhaseStepOutputCheck =
    PhaseStepOutputCheck.Block(
      FeatureTaskRuntimeAttemptBudgets.validateRemainingUnchangedBlockReason(stepId),
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

  private fun remainingFailures(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String =
    JsonCodec.anyToStringAnyMap(outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS])
      ?.get(SharedPayloadKeys.VALUE)
      ?.let { it as? String ?: JsonCodec.valueToJsonString(it) }
      .orEmpty()
}

internal object AgentValidateResumeRules : PhaseResumeRules {
  override fun invalidatesResumedCompletion(
    record: FeatureTaskRuntimePhaseRecord,
    output: () -> FeatureTaskRuntimePhaseOutput?,
  ): Boolean {
    val envelope = output()?.normalizedOutput?.envelopeWireMap()
    return envelope == null ||
      (envelope[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED
  }
}
