package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput

private const val VALIDATION_REPAIR_PLAN_KEY = "validation_repair_plan"

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

  internal fun repairSegmentOutput(
    run: PhaseRun,
    iteration: Int,
  ): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload =
        """{"${SharedPayloadKeys.CONTRACT_VERSION}":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",""" +
          """"${SharedPayloadKeys.PHASE_ID}":"${run.phaseId}",""" +
          """"${SharedPayloadKeys.STATUS}":"${WorkflowStepStatus.COMPLETED.wireValue}",""" +
          """"${SharedPayloadKeys.SUMMARY}":"Gate repair segment.",""" +
          """"${SharedPayloadKeys.PRODUCED_OUTPUTS}":{}}""",
    )

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
        produced?.get(VALIDATION_REPAIR_PLAN_KEY)?.let { put(VALIDATION_REPAIR_PLAN_KEY, it) }
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

internal object PackBuildTriagePlan {
  internal fun extract(output: FeatureTaskRuntimePhaseOutput): ValidationGateTriageResult {
    val produced =
      outputEnvelopeOf(output)
        ?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
        ?: return ValidationGateTriageResult.Empty
    planFromValue(produced[SharedPayloadKeys.VALUE])?.let { return it }
    val directPlan = planProse(produced[VALIDATION_REPAIR_PLAN_KEY])
    return if (!directPlan.isNullOrBlank()) {
      ValidationGateTriageResult.Captured(directPlan)
    } else {
      ValidationGateTriageResult.Empty
    }
  }

  private fun outputEnvelopeOf(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?>? =
    output.normalizedOutput?.envelopeWireMap()?.takeIf { it.isNotEmpty() }
      ?: JsonCodec.parseObjectOrNull(output.payload)?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)

  private fun planFromValue(value: Any?): ValidationGateTriageResult? {
    val valueText = (value as? String)?.takeIf(String::isNotBlank) ?: return null
    val inner =
      JsonCodec.parseObjectOrNull(valueText)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val planFromValue = inner?.let { planProse(it[VALIDATION_REPAIR_PLAN_KEY]) }
    if (!planFromValue.isNullOrBlank()) {
      return ValidationGateTriageResult.Captured(planFromValue)
    }
    return if (inner == null) ValidationGateTriageResult.Captured(valueText) else null
  }

  private fun planProse(raw: Any?): String? =
    when (raw) {
      is String -> raw.takeIf { it.isNotBlank() }
      null -> null
      else ->
        JsonCodec.mapToJsonString(
          JsonCodec.anyToStringAnyMap(raw) ?: mapOf(VALIDATION_REPAIR_PLAN_KEY to raw),
        ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
    }
}
