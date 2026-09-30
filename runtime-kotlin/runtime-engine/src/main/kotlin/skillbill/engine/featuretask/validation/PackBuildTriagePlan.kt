package skillbill.engine.featuretask.validation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput

internal object PackGateOutputKeys {
  const val VALIDATION_REPAIR_PLAN = "validation_repair_plan"
}

internal object PackBuildTriagePlan {
  internal fun extract(output: FeatureTaskRuntimePhaseOutput): ValidationGateTriageResult {
    val produced =
      outputEnvelopeOf(output)
        ?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
        ?: return ValidationGateTriageResult.Empty
    planFromValue(produced[SharedPayloadKeys.VALUE])?.let { return it }
    val directPlan = planProse(produced[PackGateOutputKeys.VALIDATION_REPAIR_PLAN])
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
    val planFromValue = inner?.let { planProse(it[PackGateOutputKeys.VALIDATION_REPAIR_PLAN]) }
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
          JsonCodec.anyToStringAnyMap(raw) ?: mapOf(PackGateOutputKeys.VALIDATION_REPAIR_PLAN to raw),
        ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
    }
}
