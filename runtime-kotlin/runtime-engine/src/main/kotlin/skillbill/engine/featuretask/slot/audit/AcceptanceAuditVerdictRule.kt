package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal class AcceptanceAuditVerdictRule(
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskRuntimeStepVerdictRule {
  private val recordedFallbacks = mutableSetOf<String>()

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
    if (wireVerdict == FeatureTaskRuntimeVerdict.SATISFIED) return FeatureTaskRuntimeVerdict.SATISFIED
    if (wireVerdict != null && wireVerdict != UNKNOWN_WORD_DEFAULT && recordedFallbacks.add(wireVerdict.wireValue)) {
      diagnostics.warning(
        "Audit verdict '${wireVerdict.wireValue}' conflicts with its remaining criteria; " +
          "using '${UNKNOWN_WORD_DEFAULT.wireValue}'.",
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
          "(audit phase output); report remaining criteria without repairing them."
      }
      return null
    }
  }
}
