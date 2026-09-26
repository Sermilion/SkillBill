package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal object AgentValidateStepHooks : PhaseStepHooks {
  override val blockedOutputDisposition: FeatureTaskRuntimeFailureDisposition
    get() = FeatureTaskRuntimeFailureDisposition.RETRYABLE

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.BLOCKED) {
      return PhaseStepOutputCheck.Accept
    }
    val verdict = outputMap[SharedPayloadKeys.VERDICT] as? String
    return when (verdict) {
      FeatureTaskRuntimeVerdict.PROGRESS.wireValue -> PhaseStepOutputCheck.ContinueRepair(remainingFailures(outputMap))
      FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue -> noProgressBlock()
      else -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "Blocked '${run.phaseId}' output of workflow '${run.request.workflowId}' carried " +
            "${verdict?.let { "unknown verdict '$it'" } ?: "no verdict"}; counted as " +
            "${FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue}.",
        )
        noProgressBlock()
      }
    }
  }

  private fun noProgressBlock(): PhaseStepOutputCheck =
    PhaseStepOutputCheck.Block(
      FeatureTaskRuntimeAttemptBudgets.validateRemainingUnchangedBlockReason(),
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

  private fun remainingFailures(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String =
    JsonCodec.anyToStringAnyMap(outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS])
      ?.get(SharedPayloadKeys.VALUE)
      ?.let { it as? String ?: JsonCodec.valueToJsonString(it) }
      .orEmpty()
}
