package skillbill.engine.featuretask.qa.report

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaOutcomePayloadKeys

internal object QaPhaseOutputKeys {
  const val VERDICT = "qa_verdict"
  const val REASON = "qa_reason"
  const val FAILURE_KIND = "failure_kind"
  const val REPORT_TEXT = "qa_report_text"
  const val REPORT_REF = "qa_report_ref"
  const val HEAD_REVISION = "qa_head_revision"
  const val TREE_IDENTITY = "qa_tree_identity"
  const val EVIDENCE_PREFIX = "qa_evidence_prefix"
  const val OPEN_FINDINGS = "qa_open_findings"
  const val WARNINGS = "qa_warnings"
  const val REPAIRS_EXHAUSTED = "qa_repairs_exhausted"
  const val LEGACY_RECEIPT = "qa_receipt"
  const val LEGACY_RECEIPT_REF = "qa_receipt_ref"
}

internal fun hasQaOutput(produced: Map<String, Any?>?): Boolean =
  produced != null &&
    (produced.containsKey(QaPhaseOutputKeys.VERDICT) || produced.containsKey(QaPhaseOutputKeys.LEGACY_RECEIPT))

internal fun qaVerdictOf(produced: Map<String, Any?>): QaVerdict? =
  QaVerdict.fromWire(produced[QaPhaseOutputKeys.VERDICT] as? String)
    ?: QaVerdict.fromWire(legacyReceipt(produced)?.get(QaOutcomePayloadKeys.VERDICT) as? String)

internal fun qaReportRef(produced: Map<String, Any?>): String? =
  produced[QaPhaseOutputKeys.REPORT_REF] as? String ?: produced[QaPhaseOutputKeys.LEGACY_RECEIPT_REF] as? String

internal fun qaOutcomeReason(
  verdict: QaVerdict,
  produced: Map<String, Any?>,
): String? {
  val verdictReason =
    when (verdict) {
      QaVerdict.SKIPPED, QaVerdict.INCOMPLETE -> produced[QaPhaseOutputKeys.REASON] as? String ?: legacyReason(produced)
      QaVerdict.FINDINGS_OPEN -> openFindingsReason(produced)
      QaVerdict.PASSED -> null
    }
  val warnings = (produced[QaPhaseOutputKeys.WARNINGS] as? List<*>).orEmpty().filterIsInstance<String>()
  return (listOfNotNull(verdictReason) + warnings)
    .filter(String::isNotBlank)
    .distinct()
    .joinToString("; ")
    .takeIf(String::isNotBlank)
}

private fun legacyReceipt(produced: Map<String, Any?>): Map<String, Any?>? =
  JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.LEGACY_RECEIPT])

private fun legacyReason(produced: Map<String, Any?>): String? {
  val receipt = legacyReceipt(produced) ?: return null
  return listOfNotNull(
    (receipt[QaOutcomePayloadKeys.SKIP_REASON] ?: receipt[QaOutcomePayloadKeys.INCOMPLETE_REASON]) as? String,
    (receipt[QaOutcomePayloadKeys.REASON_DETAIL] as? String)?.trim()?.takeIf(String::isNotBlank),
  ).joinToString(" · ").takeIf(String::isNotBlank)
}

private fun openFindingsReason(produced: Map<String, Any?>): String? {
  if (produced[QaPhaseOutputKeys.REPAIRS_EXHAUSTED] != true) return null
  val ids =
    JsonCodec.anyToStringAnyMapList(produced[QaPhaseOutputKeys.OPEN_FINDINGS]).orEmpty()
      .mapNotNull { it[QaOutcomePayloadKeys.ID] as? String }
  return ids.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = "findings left open: ")
}
