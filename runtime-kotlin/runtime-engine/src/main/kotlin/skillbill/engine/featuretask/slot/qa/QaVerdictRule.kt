package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.qa.report.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.report.hasQaOutput
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeQaOpenFindings
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal object QaVerdictRule : FeatureTaskRuntimeStepVerdictRule {
  override fun verdictFor(
    wireVerdict: FeatureTaskRuntimeVerdict?,
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): FeatureTaskRuntimeVerdict {
    if (wireVerdict == FeatureTaskRuntimeVerdict.QA_INCOMPLETE) return FeatureTaskRuntimeVerdict.QA_INCOMPLETE
    val status = outputObject?.get(SharedPayloadKeys.STATUS) as? String
    if (status == "blocked" || status == "failed") return FeatureTaskRuntimeVerdict.BLOCK
    val produced =
      JsonCodec.anyToStringAnyMap(outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
        ?: error("normalized QA output has no produced_outputs object.")
    check(hasQaOutput(produced)) { "normalized QA output has no runtime-settled QA verdict." }
    if (produced[QaPhaseOutputKeys.REPAIRS_EXHAUSTED] == true) return FeatureTaskRuntimeVerdict.ADVANCE
    val openFindings =
      produced[QaPhaseOutputKeys.OPEN_FINDINGS] as? List<*> ?: error("runtime QA open findings are missing.")
    val retainedFindings =
      decodeQaOpenFindings(
        values = openFindings,
        acceptLegacyReceiptRef = true,
        invalid = { reason -> error(reason) },
      )
    return if (retainedFindings.isEmpty()) FeatureTaskRuntimeVerdict.ADVANCE else FeatureTaskRuntimeVerdict.QA_FINDINGS
  }
}
