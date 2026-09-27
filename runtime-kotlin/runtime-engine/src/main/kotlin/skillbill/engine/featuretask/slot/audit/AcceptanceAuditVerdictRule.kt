package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.feature.FeatureTaskRuntimeAuditRemainingAcInterpretation
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeAuditRemainingAcResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal class AcceptanceAuditVerdictRule(
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskRuntimeStepVerdictRule {
  private val recordedFallbacks: MutableSet<Map<String, Any?>> = mutableSetOf()

  override fun verdictFor(
    wireVerdict: FeatureTaskRuntimeVerdict?,
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): FeatureTaskRuntimeVerdict {
    val status = (outputObject?.get(SharedPayloadKeys.STATUS) as? String)?.trim()?.lowercase()
    if (status == "blocked" || status == "failed") {
      require(wireVerdict == null) {
        "blocked or failed audit phase output must omit verdict."
      }
      return FeatureTaskRuntimeVerdict.ADVANCE
    }
    if (wireVerdict == FeatureTaskRuntimeVerdict.SATISFIED) {
      return FeatureTaskRuntimeVerdict.SATISFIED
    }
    if (
      FeatureTaskRuntimeAuditRemainingAcInterpretation.interpret(auditProseValue(outputObject))
        is FeatureTaskRuntimeAuditRemainingAcResult.EmptyRemainingList
    ) {
      return FeatureTaskRuntimeVerdict.SATISFIED
    }
    if (recordedFallbacks.add(outputObject?.toMap().orEmpty())) {
      val observed =
        wireVerdict?.let { "Audit verdict '${it.wireValue}' is not an audit verdict word" }
          ?: "Audit phase output carries no verdict word"
      diagnostics.warning(
        "$observed (${FeatureTaskRuntimeVerdict.AUDIT_VERDICTS.joinToString { it.wireValue }}); it settles to " +
          "'${UNKNOWN_WORD_DEFAULT.wireValue}'.",
      )
    }
    return UNKNOWN_WORD_DEFAULT
  }

  companion object {
    val UNKNOWN_WORD_DEFAULT: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict.ADVANCE

    fun removedVerdictRejection(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String? {
      val wire = (outputMap[SharedPayloadKeys.VERDICT] as? String)?.trim()
      if (wire == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue) {
        return "Feature-task-runtime verdict '${FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue}' is removed " +
          "(audit phase output); repair gaps in this session and emit satisfied."
      }
      return null
    }

    fun auditProseValue(outputObject: FeatureTaskRuntimeWorkflowArtifactMap?): String? =
      outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get(SharedPayloadKeys.VALUE)
        ?.toString()
        ?.takeIf(String::isNotBlank)
  }
}
