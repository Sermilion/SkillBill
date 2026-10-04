package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.validation.repairSegmentOutput
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

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
  ): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      output = PhaseOutput(value = outputText.trim()),
    )

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
