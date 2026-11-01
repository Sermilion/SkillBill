package skillbill.engine.featuretask.qa.report

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaOutcomePayloadKeys

public object QaPhaseOutputKeys {
  public const val VERDICT: String = "qa_verdict"
  public const val REASON: String = "qa_reason"
  public const val FAILURE_KIND: String = "failure_kind"
  public const val REPORT_TEXT: String = "qa_report_text"
  public const val REPORT_REF: String = "qa_report_ref"
  internal const val HEAD_REVISION = "qa_head_revision"
  public const val TREE_IDENTITY: String = "qa_tree_identity"
  public const val EVIDENCE_PREFIX: String = "qa_evidence_prefix"
  public const val OPEN_FINDINGS: String = "qa_open_findings"
  public const val WARNINGS: String = "qa_warnings"
  internal const val REPAIRS_EXHAUSTED = "qa_repairs_exhausted"
  public const val LEGACY_RECEIPT: String = "qa_receipt"
  public const val LEGACY_RECEIPT_REF: String = "qa_receipt_ref"
}

internal fun hasQaOutput(produced: Map<String, Any?>?): Boolean =
  produced != null &&
    (produced.containsKey(QaPhaseOutputKeys.VERDICT) || produced.containsKey(QaPhaseOutputKeys.LEGACY_RECEIPT))

internal fun qaVerdictOf(produced: Map<String, Any?>): QaVerdict? =
  QaVerdict.fromWire(produced[QaPhaseOutputKeys.VERDICT] as? String)
    ?: QaVerdict.fromWire(legacyReceipt(produced)?.get(QaOutcomePayloadKeys.VERDICT) as? String)

internal fun qaReportRef(produced: Map<String, Any?>): String? =
  produced[QaPhaseOutputKeys.REPORT_REF] as? String ?: produced[QaPhaseOutputKeys.LEGACY_RECEIPT_REF] as? String

public fun qaOutcomeReason(
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

public fun legacyReason(produced: Map<String, Any?>): String? {
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
