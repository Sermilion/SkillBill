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
    return when (wireVerdict) {
      FeatureTaskRuntimeVerdict.SATISFIED, FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED -> wireVerdict
      FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED -> FeatureTaskRuntimeVerdict.ADVANCE
      else -> fallbackVerdict(wireVerdict)
    }
  }

  private fun fallbackVerdict(wireVerdict: FeatureTaskRuntimeVerdict?): FeatureTaskRuntimeVerdict {
    if (wireVerdict != null && wireVerdict != UNKNOWN_WORD_DEFAULT && recordedFallbacks.add(wireVerdict.wireValue)) {
      diagnostics.warning(
        "Audit verdict '${wireVerdict.wireValue}' is unknown; using '${UNKNOWN_WORD_DEFAULT.wireValue}'.",
      )
    }
    return UNKNOWN_WORD_DEFAULT
  }

  companion object {
    val UNKNOWN_WORD_DEFAULT: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict.ADVANCE
  }
}
