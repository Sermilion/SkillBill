package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.qa.receipt.QaAssertionOutcome
import skillbill.engine.featuretask.qa.receipt.QaIncompleteReason
import skillbill.engine.featuretask.qa.receipt.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.error.featuretask.qa.qaReceiptUnsupportedClaim
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeQaOpenFindings
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding

internal class QaVerdictRule(
  private val validator: QaReceiptValidator,
) : FeatureTaskRuntimeStepVerdictRule {
  override fun verdictFor(
    wireVerdict: FeatureTaskRuntimeVerdict?,
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): FeatureTaskRuntimeVerdict {
    val status = outputObject?.get(SharedPayloadKeys.STATUS) as? String
    if (status == "blocked" || status == "failed") return FeatureTaskRuntimeVerdict.BLOCK
    val produced =
      JsonCodec.anyToStringAnyMap(outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
        ?: invalid("normalized QA output has no produced_outputs object.")
    val rawReceipt =
      JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.RECEIPT])
        ?: invalid("normalized QA output has no runtime-validated receipt.")
    val expected =
      QaExpectedIdentity(
        headRevision = produced.text(QaPhaseOutputKeys.HEAD_REVISION),
        treeIdentity = produced.text(QaPhaseOutputKeys.TREE_IDENTITY),
        evidencePrefix = produced.text(QaPhaseOutputKeys.EVIDENCE_PREFIX),
      )
    val receipt = validator.validate(JsonCodec.mapToJsonString(rawReceipt), expected)
    val retainedFindings = produced.findings(QaPhaseOutputKeys.OPEN_FINDINGS)
    val acceptedCriteria = produced.stringSet(QaPhaseOutputKeys.CRITERION_IDS)
    val referencedCriteria =
      (
        receipt.findings.flatMap { it.affectedCriteria } +
          receipt.assertions.flatMap { it.criteria } +
          retainedFindings.flatMap(QaOpenFinding::affectedCriteria)
      ).toSet()
    val invalidCriterion = referencedCriteria.firstOrNull { it !in acceptedCriteria }
    if (invalidCriterion != null) {
      throw qaReceiptUnsupportedClaim(
        "criterion reference '$invalidCriterion' is not in the admitted acceptance-criterion catalog.",
      )
    }
    if (retainedFindings.isNotEmpty()) {
      return FeatureTaskRuntimeVerdict.QA_FINDINGS
    }
    if (receipt.assertions.any { it.outcome == QaAssertionOutcome.FAIL }) {
      return FeatureTaskRuntimeVerdict.QA_FINDINGS
    }
    return when (receipt.verdict) {
      QaVerdict.PASSED, QaVerdict.SKIPPED -> FeatureTaskRuntimeVerdict.ADVANCE
      QaVerdict.FINDINGS_OPEN -> FeatureTaskRuntimeVerdict.QA_FINDINGS
      QaVerdict.INCOMPLETE ->
        if (receipt.incompleteReason in INFRASTRUCTURE_REASONS) {
          FeatureTaskRuntimeVerdict.ADVANCE
        } else {
          FeatureTaskRuntimeVerdict.BLOCK
        }
    }
  }

  private fun Map<String, Any?>.text(key: String): String =
    this[key] as? String ?: invalid("runtime QA identity field '$key' is missing.")

  private fun Map<String, Any?>.stringSet(key: String): Set<String> =
    (this[key] as? List<*>)?.map {
      it as? String ?: invalid("runtime QA field '$key' is malformed.")
    }?.toSet()
      ?: invalid("runtime QA field '$key' is missing.")

  private fun Map<String, Any?>.findings(key: String): List<QaOpenFinding> =
    decodeQaOpenFindings(
      values = this[key] as? List<*> ?: invalid("runtime QA field '$key' is missing."),
      acceptLegacyReceiptRef = true,
      invalid = ::invalid,
    )

  private fun invalid(reason: String): Nothing = throw invalidQaReceipt(reason)

  private companion object {
    val INFRASTRUCTURE_REASONS =
      setOf(
        QaIncompleteReason.INTERRUPTED,
        QaIncompleteReason.AUTOMATION_BROKEN,
        QaIncompleteReason.BUDGET_EXHAUSTED,
        QaIncompleteReason.DEVICE_LOST,
      )
  }
}
