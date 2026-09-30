package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.validation.PackGateOutputKeys
import skillbill.engine.featuretask.validation.repairSegmentOutput
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput

internal object PackBuildStepHooks : PhaseStepHooks {
  override val carriesPackBuildCommand: Boolean = true

  override fun earlyOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome? =
    when {
      run.validationGateTriage -> PhaseOutcome.completed(triageSegmentOutput(run, iteration, outputText))
      runtimeOwnedGateTurn(run) && !operatorTerminal(outputText) ->
        PhaseOutcome.completed(repairSegmentOutput(run, iteration))
      else -> null
    }

  private fun triageSegmentOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): FeatureTaskRuntimePhaseOutput {
    val produced =
      looseOutputEnvelope(outputText)?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
    val captured =
      buildMap {
        produced?.get(SharedPayloadKeys.VALUE)?.let { put(SharedPayloadKeys.VALUE, it) }
        produced?.get(PackGateOutputKeys.VALIDATION_REPAIR_PLAN)?.let {
          put(PackGateOutputKeys.VALIDATION_REPAIR_PLAN, it)
        }
      }
    return FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload =
        JsonCodec.mapToJsonString(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to run.phaseId,
            SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
            SharedPayloadKeys.SUMMARY to "Gate triage segment.",
            SharedPayloadKeys.PRODUCED_OUTPUTS to captured,
          ),
        ),
    )
  }

  private fun runtimeOwnedGateTurn(run: PhaseRun): Boolean =
    !run.agentRunValidateFallback &&
      (run.validationGateRepair || run.validationGateRepairTurn > 0 || run.validationGateFindings != null)

  private fun operatorTerminal(outputText: String): Boolean =
    looseOutputEnvelope(outputText)?.let {
      !FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(it).retryOnResume
    } == true

  private fun looseOutputEnvelope(outputText: String): FeatureTaskRuntimeWorkflowArtifactMap? {
    val trimmed = outputText.trim()
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    val parsed =
      JsonCodec.parseObjectOrNull(trimmed)
        ?: trimmed.takeIf { start in 0..<end }?.let { JsonCodec.parseObjectOrNull(it.substring(start, end + 1)) }
    return parsed?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))?.toWorkflowArtifactMap() }
  }
}
