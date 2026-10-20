package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys

internal object QaPhaseOutputKeys {
  const val RECEIPT = "qa_receipt"
  const val HEAD_REVISION = "qa_head_revision"
  const val TREE_IDENTITY = "qa_tree_identity"
  const val EVIDENCE_PREFIX = "qa_evidence_prefix"
  const val RECEIPT_REF = "qa_receipt_ref"
  const val CRITERION_IDS = "qa_criterion_ids"
  const val OPEN_FINDINGS = "qa_open_findings"
  const val WARNINGS = "qa_warnings"
  const val REPAIRS_EXHAUSTED = "qa_repairs_exhausted"
}

internal fun qaOutcomeReason(
  verdict: QaVerdict,
  receipt: Map<String, Any?>,
  produced: Map<String, Any?>,
): String? =
  when (verdict) {
    QaVerdict.SKIPPED -> receipt[QaReceiptPayloadKeys.SKIP_REASON] as? String
    QaVerdict.INCOMPLETE -> receipt[QaReceiptPayloadKeys.INCOMPLETE_REASON] as? String
    QaVerdict.FINDINGS_OPEN -> openFindingsReason(produced)
    QaVerdict.PASSED -> null
  }?.takeIf(String::isNotBlank)

private fun openFindingsReason(produced: Map<String, Any?>): String? {
  if (produced[QaPhaseOutputKeys.REPAIRS_EXHAUSTED] != true) return null
  val ids =
    JsonCodec.anyToStringAnyMapList(produced[QaPhaseOutputKeys.OPEN_FINDINGS]).orEmpty()
      .mapNotNull { it[QaReceiptPayloadKeys.ID] as? String }
  return ids.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = "findings left open: ")
}
